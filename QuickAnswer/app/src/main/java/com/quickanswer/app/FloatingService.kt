package com.quickanswer.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.quickanswer.app.databinding.FloatBallBinding
import com.quickanswer.app.databinding.FloatPanelBinding
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/**
 * 悬浮窗前台服务，支持两种工作模式（面板内可实时切换）：
 *
 *  - 解题模式：悬浮球 → 截屏(MediaProjection) → ① 千问视觉读题（截图转文字，含 LaTeX）
 *    → ② DeepSeek 答题；
 *  - 日常模式：悬浮球 → 打开面板 →（文字/语音）说出需求 → 发送 → 隐藏悬浮层截屏
 *    → 千问视觉「看着屏幕」直接给出操作指引或事实结论。
 *
 * 答案为内嵌 KaTeX 的 WebView 渲染（完整支持 LaTeX），题目区用 MathText 轻量渲染。
 */
class FloatingService : Service() {

    private lateinit var windowManager: WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var ball: FloatBallBinding? = null
    private var panel: FloatPanelBinding? = null
    private lateinit var ballParams: WindowManager.LayoutParams
    private lateinit var panelParams: WindowManager.LayoutParams

    private var projection: MediaProjection? = null
    private var capture: ScreenCapture? = null
    private var client: ChatClient? = null

    /** 语音识别（日常模式输入用），仅在需要时创建 */
    private var speech: SpeechRecognizer? = null
    private var listening = false

    /** 防止截屏/识别阶段重复触发 */
    private var busy = false

    /** 答案原文累积缓冲：流式增量先累积，再整体交给 WebView 渲染，保证公式跨 chunk 也能正确解析 */
    private val answerBuffer = StringBuilder()

    /** WebView 中 answer.html 是否已加载完成（未完成时的渲染请求先缓存） */
    private var webReady = false
    private var pendingAnswer: String? = null

    /** 流式渲染节流：最多每 RENDER_THROTTLE_MS 触发一次 WebView 重绘 */
    private var renderScheduled = false
    private val renderTick = Runnable {
        renderScheduled = false
        renderAnswer(answerBuffer.toString())
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // 必须在 getMediaProjection 之前先进入前台（Android 14+ 要求）
                startForegroundCompat()
                initProjection(intent)
                showBall()
            }
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------- 前台通知

    private fun startForegroundCompat() {
        val channelId = "qa_foreground"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(R.drawable.ic_stat)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            // 日常模式需在后台使用麦克风做语音输入：仅在已授予录音权限时追加 microphone 类型，
            // 否则 startForeground 会抛 SecurityException。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(NOTIF_ID, notification, type)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    // ---------------------------------------------------------------- 截屏初始化

    private fun initProjection(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
        if (resultCode == 0 || data == null) {
            Toast.makeText(this, getString(R.string.panel_status_capture_fail), Toast.LENGTH_SHORT).show()
            stopSelf()
            return
        }
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mp = mpm.getMediaProjection(resultCode, data)
            if (mp == null) {
                stopSelf()
                return
            }
            projection = mp
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.w(TAG, "MediaProjection stopped by system")
                    capture?.release()
                    capture = null
                }
            }, handler)

            val size = screenSize()
            val dpi = resources.displayMetrics.densityDpi
            capture = ScreenCapture(this, mp, size.x, size.y, dpi).also { it.start() }
        } catch (e: Exception) {
            Log.e(TAG, "initProjection failed", e)
            Toast.makeText(this, getString(R.string.panel_status_capture_fail), Toast.LENGTH_SHORT).show()
            stopSelf()
        }
    }

    private fun screenSize(): Point {
        val point = Point()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = windowManager.currentWindowMetrics.bounds
                point.set(bounds.width(), bounds.height())
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealSize(point)
            }
        } catch (e: Exception) {
            Log.w(TAG, "currentWindowMetrics unavailable, fallback to displayMetrics", e)
            val dm = resources.displayMetrics
            point.set(dm.widthPixels, dm.heightPixels)
        }
        return point
    }

    // ---------------------------------------------------------------- 悬浮球

    private fun showBall() {
        if (ball != null) return
        val binding = FloatBallBinding.inflate(LayoutInflater.from(this))
        ball = binding

        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        // 注意：addView 会用这里的 LayoutParams 覆盖视图 XML 声明的尺寸，
        // 因此悬浮球必须在 WM 参数上显式指定 56dp，否则会被压成 wrap_content 的默认大小。
        val ballSize = (BALL_SIZE_DP * resources.displayMetrics.density).toInt()
        val margin = (BALL_MARGIN_DP * resources.displayMetrics.density).toInt()
        val bounds = overlayBounds()
        // 可拖拽范围：左右留出 margin（避开左/右边缘的返回手势区），整个球体保持在可用区域内。
        val minX = margin
        val maxX = (bounds.x - ballSize - margin).coerceAtLeast(minX)
        val maxY = (bounds.y - ballSize).coerceAtLeast(0)
        ballParams = WindowManager.LayoutParams(
            ballSize,
            ballSize,
            overlayType(),
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = minX
            y = (220 * resources.displayMetrics.density).toInt().coerceIn(0, maxY)
        }

        binding.root.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            private var startX = 0
            private var startY = 0
            private var moved = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        startX = ballParams.x
                        startY = ballParams.y
                        moved = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        if (abs(dx) > 12 || abs(dy) > 12) moved = true
                        ballParams.x = (startX + dx).toInt().coerceIn(minX, maxX)
                        ballParams.y = (startY + dy).toInt().coerceIn(0, maxY)
                        windowManager.updateViewLayout(binding.root, ballParams)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) onBallTap()
                        return true
                    }
                }
                return false
            }
        })

        windowManager.addView(binding.root, ballParams)
        applyMode()
    }

    /** 点击悬浮球：解题模式直接截屏解答；日常模式先打开面板让用户输入需求。 */
    private fun onBallTap() {
        if (Prefs.mode(this) == Prefs.MODE_DAILY) {
            showPanel()
            panel?.tvStatus?.text = getString(R.string.panel_status_idle_daily)
        } else {
            doCapture()
        }
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    /**
     * 悬浮窗坐标可用区域（与 WindowManager.LayoutParams 的 x/y 同一坐标系）。
     * TYPE_APPLICATION_OVERLAY 窗口的父框架从状态栏下沿开始，因此高度需扣掉顶部状态栏 inset。
     */
    private fun overlayBounds(): Point {
        val dm = resources.displayMetrics
        var w = dm.widthPixels
        var h = dm.heightPixels
        var topInset = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                val metrics = windowManager.currentWindowMetrics
                w = metrics.bounds.width()
                h = metrics.bounds.height()
                topInset = metrics.windowInsets
                    .getInsets(android.view.WindowInsets.Type.systemBars()).top
            }
        }
        return Point(w, h - topInset)
    }

    // ---------------------------------------------------------------- 结果面板

    private fun ensurePanel(): FloatPanelBinding {
        panel?.let { return it }
        val binding = FloatPanelBinding.inflate(LayoutInflater.from(this))
        panel = binding

        val dm = resources.displayMetrics
        // FLAG_HARDWARE_ACCELERATED：叠加层默认可能非硬件加速，会导致 WebView 渲染空白
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        // 同理：面板宽高必须在 WM 参数上显式指定，否则会被压成内容尺寸。
        // 高度固定后，内部上下两区才能按 weight 等分。
        val panelWidth = (PANEL_WIDTH_DP * dm.density).toInt()
        val panelHeight = (PANEL_HEIGHT_DP * dm.density).toInt()
        val bounds = overlayBounds()
        val maxX = (bounds.x - panelWidth).coerceAtLeast(0)
        val maxY = (bounds.y - panelHeight).coerceAtLeast(0)
        panelParams = WindowManager.LayoutParams(
            panelWidth,
            panelHeight,
            overlayType(),
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = maxX / 2
            y = maxY / 2
            // 日常模式需要软键盘输入：默认不自动弹出，由用户点击输入框时再显示
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
        }

        // 拖动：按住顶部标题区（panelHeader）即可移动面板，并限制在可用区域内。
        binding.panelHeader.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            private var startX = 0
            private var startY = 0

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        startX = panelParams.x
                        startY = panelParams.y
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        panelParams.x = (startX + (event.rawX - downX)).toInt().coerceIn(0, maxX)
                        panelParams.y = (startY + (event.rawY - downY)).toInt().coerceIn(0, maxY)
                        windowManager.updateViewLayout(binding.root, panelParams)
                        return true
                    }
                }
                return false
            }
        })

        setupWebView(binding.webAnswer)

        binding.btnClose.setOnClickListener {
            hideKeyboard()
            panel?.root?.visibility = View.GONE
        }
        binding.btnReread.setOnClickListener {
            if (Prefs.mode(this) == Prefs.MODE_DAILY) sendDaily() else doCapture()
        }
        binding.btnCopy.setOnClickListener {
            val text = answerBuffer.toString()
            if (text.isNotEmpty()) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("answer", text))
                toast(getString(R.string.panel_status_copied))
            }
        }
        binding.segSolve.setOnClickListener { setMode(Prefs.MODE_SOLVE) }
        binding.segDaily.setOnClickListener { setMode(Prefs.MODE_DAILY) }
        binding.btnVoice.setOnClickListener { toggleVoice() }
        binding.etRequest.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendDaily()
                true
            } else {
                false
            }
        }

        windowManager.addView(binding.root, panelParams)
        applyMode()
        return binding
    }

    /** 初始化答案区 WebView：加载离线模板（内嵌 KaTeX + marked），开启 JS 以便流式重绘。 */
    private fun setupWebView(web: WebView) {
        web.setBackgroundColor(Color.TRANSPARENT)
        web.isVerticalScrollBarEnabled = true
        web.settings.javaScriptEnabled = true
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                webReady = true
                pendingAnswer?.let { renderAnswer(it) }
                pendingAnswer = null
            }
        }
        web.loadUrl(ANSWER_PAGE)
    }

    /**
     * 用累积的 markdown/LaTeX 原文重绘答案区。
     * 通过 JSON 字符串转义把内容安全地传给页面内的 renderAnswer()，避免注入与截断。
     */
    private fun renderAnswer(markdown: String) {
        val p = panel ?: return
        if (!webReady) {
            pendingAnswer = markdown
            return
        }
        val mdArg = JSONObject.quote(markdown)
        val emptyArg = JSONObject.quote(getString(R.string.panel_answer_empty))
        p.webAnswer.evaluateJavascript("window.renderAnswer($mdArg, $emptyArg);", null)
    }

    /** 节流渲染：流式增量先入缓冲，最多每 RENDER_THROTTLE_MS 重绘一次。 */
    private fun scheduleRender() {
        if (renderScheduled) return
        renderScheduled = true
        handler.postDelayed(renderTick, RENDER_THROTTLE_MS)
    }

    private fun renderNow() {
        handler.removeCallbacks(renderTick)
        renderScheduled = false
        renderAnswer(answerBuffer.toString())
    }

    private fun showPanel() {
        val binding = ensurePanel()
        binding.root.visibility = View.VISIBLE
    }

    // ---------------------------------------------------------------- 模式

    /** 依当前模式刷新面板与悬浮球的文案、可见性与可获焦状态（切模式与建面板时调用）。 */
    private fun applyMode() {
        val daily = Prefs.mode(this) == Prefs.MODE_DAILY
        ball?.ball?.text = getString(if (daily) R.string.ball_text_daily else R.string.ball_text)

        val p = panel ?: return
        p.zoneQuestion.visibility = if (daily) View.GONE else View.VISIBLE
        p.dailyInputBar.visibility = if (daily) View.VISIBLE else View.GONE
        if (!daily) {
            p.etRequest.setText("")
            p.etRequest.clearFocus()
        }
        p.tvTitle.text = getString(if (daily) R.string.panel_title_daily else R.string.panel_title)
        p.tvAnswerLabel.text =
            getString(if (daily) R.string.panel_answer_label_daily else R.string.panel_answer_label)
        p.btnReread.text = getString(if (daily) R.string.btn_send else R.string.btn_reread)
        styleSeg(p.segSolve, !daily)
        styleSeg(p.segDaily, daily)
        setPanelFocusable(daily)
    }

    private fun setMode(mode: String) {
        if (Prefs.mode(this) == mode) return
        Prefs.setMode(this, mode)
        val daily = mode == Prefs.MODE_DAILY
        answerBuffer.setLength(0)
        renderAnswer("")
        panel?.tvStatus?.text =
            getString(if (daily) R.string.panel_status_idle_daily else R.string.panel_status_idle)
        applyMode()
    }

    private fun styleSeg(view: TextView, selected: Boolean) {
        view.setBackgroundResource(
            if (selected) R.drawable.bg_segment_selected else R.drawable.bg_segment_default
        )
        view.setTextColor(ContextCompat.getColor(this, if (selected) R.color.on_dark else R.color.steel))
    }

    /** 面板是否可获焦：日常模式需要（让输入框能弹软键盘），解题模式保持不抢焦点。 */
    private fun setPanelFocusable(focusable: Boolean) {
        if (!::panelParams.isInitialized) return
        val base = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        panelParams.flags =
            if (focusable) base else base or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        panel?.root?.let { runCatching { windowManager.updateViewLayout(it, panelParams) } }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(panel?.root?.windowToken, 0)
    }

    // ---------------------------------------------------------------- 日常模式输入

    /** 发送当前需求：隐藏键盘 → 收起悬浮层截屏 → 截图+需求交千问生成指引。 */
    private fun sendDaily() {
        if (busy) return
        stopVoice()
        val request = panel?.etRequest?.text?.toString().orEmpty().trim()
        hideKeyboard()
        panel?.etRequest?.setText("")
        doCapture(request)
    }

    private fun toggleVoice() {
        if (listening) {
            stopVoice()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            toast(getString(R.string.mic_denied))
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast(getString(R.string.mic_unavailable))
            return
        }
        speech?.destroy()
        val sr = SpeechRecognizer.createSpeechRecognizer(this)
        speech = sr
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                panel?.btnVoice?.text = getString(R.string.btn_voice_listening)
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                listening = false
                panel?.btnVoice?.text = getString(R.string.btn_voice)
            }

            override fun onError(error: Int) {
                listening = false
                if (panel != null) {
                    panel?.btnVoice?.text = getString(R.string.btn_voice)
                    toast(getString(R.string.mic_error))
                }
            }

            override fun onResults(results: Bundle?) {
                listening = false
                panel?.btnVoice?.text = getString(R.string.btn_voice)
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                if (text.isNotEmpty()) panel?.etRequest?.setText(text)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                if (text.isNotEmpty()) panel?.etRequest?.setText(text)
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        listening = true
        panel?.btnVoice?.text = getString(R.string.btn_voice_listening)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        sr.startListening(intent)
    }

    private fun stopVoice() {
        if (!listening && speech == null) return
        runCatching { speech?.stopListening() }
        listening = false
        panel?.btnVoice?.text = getString(R.string.btn_voice)
    }

    private fun destroyVoice() {
        listening = false
        runCatching { speech?.destroy() }
        speech = null
    }

    // ---------------------------------------------------------------- 核心流程

    /**
     * 截屏并进入后续流程。
     * @param request 为 null 走解题模式（读题 → 答题）；非 null 走日常模式（截图+需求 → 直接给指引）。
     */
    private fun doCapture(request: String? = null) {
        if (busy) return
        cancelStream()
        stopVoice()
        busy = true
        val daily = request != null

        val p = ensurePanel()
        p.root.visibility = View.GONE
        ball?.root?.visibility = View.GONE

        // 收起悬浮层后稍等一帧再截屏，避免把悬浮球/面板截进去
        handler.postDelayed({
            Thread {
                val bitmap = capture?.capture()
                handler.post {
                    p.root.visibility = View.VISIBLE
                    ball?.root?.visibility = View.VISIBLE
                    p.tvStatus.text = getString(R.string.panel_status_capturing)
                    answerBuffer.setLength(0)
                    renderAnswer("")
                    if (!daily) p.tvQuestion.text = getString(R.string.panel_question_empty)
                }
                if (bitmap == null) {
                    handler.post {
                        p.tvStatus.text = getString(R.string.panel_status_capture_fail)
                        busy = false
                    }
                    return@Thread
                }
                val base64 = bitmapToJpegBase64(bitmap)
                bitmap.recycle()
                handler.post {
                    if (daily) startDaily(base64, request.orEmpty()) else startPipeline(base64)
                }
            }.start()
        }, CAPTURE_DELAY_MS)
    }

    /** 截图转 JPEG base64（长边限制在 [VISION_MAX_SIDE] 内，兼顾清晰度与上传体积）。 */
    private fun bitmapToJpegBase64(bitmap: Bitmap): String {
        val maxSide = maxOf(bitmap.width, bitmap.height)
        val scaled = if (maxSide > VISION_MAX_SIDE) {
            val s = VISION_MAX_SIDE.toFloat() / maxSide
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * s).toInt().coerceAtLeast(1),
                (bitmap.height * s).toInt().coerceAtLeast(1),
                true
            )
        } else {
            bitmap
        }
        val bytes = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 82, bytes)
        if (scaled !== bitmap) scaled.recycle()
        return Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
    }

    // ---------------------------------------------------------------- 解题模式流水线

    /** 启动解题流水线：先校验两把 Key，再进入「千问读题 → DeepSeek 答题」。 */
    private fun startPipeline(imageBase64: String) {
        val qwenKey = Prefs.apiKey(this, Prefs.PROVIDER_QWEN)
        val dsKey = Prefs.apiKey(this, Prefs.PROVIDER_DEEPSEEK)
        if (qwenKey.isBlank() || dsKey.isBlank()) {
            panel?.tvStatus?.text = getString(R.string.status_need_key)
            busy = false
            return
        }
        panel?.tvStatus?.text = getString(R.string.panel_status_reading)
        panel?.tvQuestion?.text = getString(R.string.panel_question_reading)
        busy = false
        readQuestion(imageBase64)
    }

    /** 第一段：千问视觉读题，把截图转成题目文字（含 LaTeX）。 */
    private fun readQuestion(imageBase64: String) {
        val key = Prefs.apiKey(this, Prefs.PROVIDER_QWEN)
        val c = ChatClient(key, Prefs.MODEL_QWEN_VISION, ChatClient.QWEN_URL)
        client = c

        val sb = StringBuilder()
        Thread {
            c.streamImage(
                imageBase64 = imageBase64,
                onDelta = { delta ->
                    sb.append(delta)
                    handler.post { panel?.tvQuestion?.text = sb.toString() }
                },
                onDone = {
                    handler.post {
                        val question = sb.toString().trim()
                        if (question.isEmpty()) {
                            panel?.tvStatus?.text = getString(R.string.panel_status_no_text)
                        } else {
                            panel?.tvQuestion?.text = MathText.render(question)
                            askModel(question)
                        }
                    }
                },
                onError = { msg ->
                    handler.post {
                        panel?.tvStatus?.text = getString(R.string.panel_status_error) + msg
                    }
                }
            )
        }.start()
    }

    /** 第二段：DeepSeek 答题，把读题结果交给所选文本模型并流式渲染。 */
    private fun askModel(question: String) {
        val key = Prefs.apiKey(this, Prefs.PROVIDER_DEEPSEEK)
        if (key.isBlank()) {
            panel?.tvStatus?.text = getString(R.string.status_need_key)
            return
        }
        panel?.tvStatus?.text = getString(R.string.panel_status_asking)
        answerBuffer.setLength(0)
        renderAnswer("")

        val model = Prefs.model(this)
        val c = ChatClient(key, model, ChatClient.endpointFor(model))
        client = c
        Thread {
            c.stream(
                question = question,
                onDelta = { delta -> handleDelta(delta) },
                onDone = { handler.post { finishStream() } },
                onError = { msg ->
                    handler.post { panel?.tvStatus?.text = getString(R.string.panel_status_error) + msg }
                }
            )
        }.start()
    }

    // ---------------------------------------------------------------- 日常模式流水线

    /** 日常模式：把截图与用户需求一起交给千问，直接流式生成操作指引/事实结论。 */
    private fun startDaily(imageBase64: String, request: String) {
        val qwenKey = Prefs.apiKey(this, Prefs.PROVIDER_QWEN)
        if (qwenKey.isBlank()) {
            panel?.tvStatus?.text = getString(R.string.status_need_qwen_key)
            busy = false
            return
        }
        panel?.tvStatus?.text = getString(R.string.panel_status_daily)
        busy = false

        val c = ChatClient(qwenKey, Prefs.MODEL_QWEN_VISION, ChatClient.QWEN_URL)
        client = c
        Thread {
            c.streamImageAsk(
                imageBase64 = imageBase64,
                request = request,
                onDelta = { delta -> handleDelta(delta) },
                onDone = { handler.post { finishStream() } },
                onError = { msg ->
                    handler.post { panel?.tvStatus?.text = getString(R.string.panel_status_error) + msg }
                }
            )
        }.start()
    }

    private fun handleDelta(delta: String) {
        handler.post {
            answerBuffer.append(delta)
            scheduleRender()
        }
    }

    private fun finishStream() {
        renderNow()
        panel?.tvStatus?.text = ""
    }

    private fun cancelStream() {
        client?.cancel()
        client = null
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------- 生命周期

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        cancelStream()
        destroyVoice()
        capture?.release()
        capture = null
        projection?.stop()
        projection = null
        ball?.let { runCatching { windowManager.removeView(it.root) } }
        panel?.let {
            runCatching { it.webAnswer.stopLoading() }
            runCatching { it.webAnswer.destroy() }
            runCatching { windowManager.removeView(it.root) }
        }
        ball = null
        panel = null
        webReady = false
        pendingAnswer = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "FloatingService"
        private const val NOTIF_ID = 1001
        private const val CAPTURE_DELAY_MS = 160L
        private const val BALL_SIZE_DP = 56
        private const val BALL_MARGIN_DP = 8
        private const val PANEL_WIDTH_DP = 320
        private const val PANEL_HEIGHT_DP = 560

        /** 流式渲染节流间隔（毫秒） */
        private const val RENDER_THROTTLE_MS = 120L

        /** 视觉直传时截图长边上限（像素），控制上传体积与响应速度 */
        private const val VISION_MAX_SIDE = 1600

        private const val ANSWER_PAGE = "file:///android_asset/answer.html"

        const val ACTION_START = "com.quickanswer.app.START"
        const val ACTION_STOP = "com.quickanswer.app.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, FloatingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, FloatingService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
