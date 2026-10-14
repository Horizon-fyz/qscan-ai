package com.quickanswer.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
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
import android.text.Editable
import android.text.TextWatcher
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
 * 悬浮窗前台服务，支持三种工作模式（面板内可实时切换）：
 *
 *  - 解题模式：悬浮球 → 截屏(MediaProjection) → ① 千问视觉读题（截图转文字，含 LaTeX）
 *    → ② DeepSeek 答题；
 *  - 日常模式：悬浮球 → 打开面板 →（文字/语音）说出需求 → 发送 → 截屏
 *    → 千问视觉「看着屏幕」直接给出操作指引或事实结论；
 *  - 实时模式：打开面板即持续观察屏幕，画面有变化就把最新一帧交千问即时回答，
 *    支持常驻的文字/语音需求。
 *
 * 截屏时面板保持可见，改由对悬浮层区域涂白遮罩来避免截到自身，因此不再闪屏。
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

    /** 翻动拼接是否进行中（解题模式）：连续截屏 → 去重叠 → 累积切片 */
    private var stitching = false
    private val stitchSlices = mutableListOf<String>()
    private var stitcher: ScrollStitcher? = null
    private val stitchTick = Runnable { grabForStitch() }

    /** 实时模式：循环观察屏幕，检测到画面变化才请求，避免无效调用。 */
    private var realtimeActive = false
    private var realtimeBusy = false
    private var lastFingerprint: IntArray? = null
    private val realtimeTick = Runnable { realtimeGrab() }

    /** 答案原文累积缓冲：流式增量先累积，再整体交给 WebView 渲染，保证公式跨 chunk 也能正确解析 */
    private val answerBuffer = StringBuilder()

    /** 模式变化监听：主界面切换模式时，本服务同步刷新悬浮球文案与面板形态。 */
    private val modeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.KEY_MODE) applyMode()
    }

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
        Prefs.registerModeListener(this, modeListener)
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

    /** 点击悬浮球：拼接中→结束拼接；解题模式直接截屏解答；日常/实时模式先打开面板。 */
    private fun onBallTap() {
        if (stitching) {
            stopStitch()
            return
        }
        when (Prefs.mode(this)) {
            Prefs.MODE_DAILY -> {
                showPanel()
                panel?.tvStatus?.text = getString(R.string.panel_status_idle_daily)
            }
            Prefs.MODE_REALTIME -> {
                showPanel()
                startRealtime()
            }
            else -> doCapture()
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
                        // 边界随当前面板尺寸动态计算，缩放后拖动仍能贴边不越界
                        val b = overlayBounds()
                        val limitX = (b.x - panelParams.width).coerceAtLeast(0)
                        val limitY = (b.y - panelParams.height).coerceAtLeast(0)
                        panelParams.x = (startX + (event.rawX - downX)).toInt().coerceIn(0, limitX)
                        panelParams.y = (startY + (event.rawY - downY)).toInt().coerceIn(0, limitY)
                        windowManager.updateViewLayout(binding.root, panelParams)
                        return true
                    }
                }
                return false
            }
        })

        // 四角拖拽缩放：拖动哪个角，就从哪个角单独缩放（仅四角，不含双指捏合）
        setupResize(binding.handleTl, CORNER_TL)
        setupResize(binding.handleTr, CORNER_TR)
        setupResize(binding.handleBl, CORNER_BL)
        setupResize(binding.handleBr, CORNER_BR)

        setupWebView(binding.webAnswer)

        binding.btnClose.setOnClickListener {
            // 先关面板，确保点击必定有可见结果；再做收尾清理
            hidePanel()
            hideKeyboard()
            stopRealtime()
        }
        binding.btnReread.setOnClickListener {
            when (Prefs.mode(this)) {
                Prefs.MODE_SOLVE -> doCapture()
                Prefs.MODE_REALTIME -> toggleRealtime()
                else -> sendDaily()
            }
        }
        binding.btnStitch.setOnClickListener { startStitch() }
        binding.btnDailyShot.setOnClickListener { sendDailyShot() }
        binding.btnCopy.setOnClickListener {
            val text = answerBuffer.toString()
            if (text.isNotEmpty()) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("answer", text))
                toast(getString(R.string.panel_status_copied))
            }
        }
        // 分享/导出：走系统分享面板（可保存到文件、便签、聊天等），内容为答案原文
        binding.btnShare.setOnClickListener {
            val text = answerBuffer.toString()
            if (text.isEmpty()) {
                toast(getString(R.string.panel_status_share_empty))
                return@setOnClickListener
            }
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            val chooser = Intent.createChooser(send, getString(R.string.btn_share)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { startActivity(chooser) }
                .onFailure { toast(getString(R.string.panel_status_error) + it.message) }
        }
        // 导出：把答案原文交给 ExportActivity，支持 Word/PDF/Markdown/文本/HTML/长图
        binding.btnExport.setOnClickListener {
            val text = answerBuffer.toString()
            if (text.isEmpty()) {
                toast(getString(R.string.export_empty))
                return@setOnClickListener
            }
            hideKeyboard()
            ExportActivity.launch(this, text, getString(R.string.export_default_title))
        }
        binding.segSolve.setOnClickListener { setMode(Prefs.MODE_SOLVE) }
        binding.segDaily.setOnClickListener { setMode(Prefs.MODE_DAILY) }
        binding.segRealtime.setOnClickListener { setMode(Prefs.MODE_REALTIME) }
        binding.btnVoice.setOnClickListener { toggleVoice() }
        binding.etRequest.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendDaily()
                true
            } else {
                false
            }
        }
        // 实时模式：需求文字/语音一旦变化，立即作废上一帧指纹，下一轮采集即按新需求重新回答
        binding.etRequest.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (realtimeActive) lastFingerprint = null
            }
        })

        // 手势兼容：面板平时「非获焦」以免抢系统的返回手势；用户点输入框时才临时转为
        // 可获焦（软键盘需要），一旦失焦立即恢复「非获焦」，把返回手势交还底层应用。
        binding.etRequest.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                setPanelFocusable(true)
                v.postDelayed({ v.requestFocus() }, 80L)
            }
            false
        }
        binding.etRequest.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                v.postDelayed({ imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT) }, 120L)
            } else {
                setPanelFocusable(false)
                hideKeyboard()
            }
        }

        windowManager.addView(binding.root, panelParams)
        applyMode()
        return binding
    }

    /**
     * 角手柄缩放：拖动某个角时，该角所在的两条边跟随手指移动，对角的边保持不动。
     * 用「窗口四条边」而非「宽高」计算，越界时锚定边也不会漂移（参照 MilQuiz 的四角缩放）。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupResize(handle: View, corner: Int) {
        // 必须可点击，否则该 View 不会成为触摸目标，后续 MOVE 事件收不到（缩放会失效）
        handle.isClickable = true
        var downX = 0f
        var downY = 0f
        var l0 = 0
        var t0 = 0
        var r0 = 0
        var b0 = 0
        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    l0 = panelParams.x
                    t0 = panelParams.y
                    r0 = panelParams.x + panelParams.width
                    b0 = panelParams.y + panelParams.height
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (abs(dx) <= dp(3) && abs(dy) <= dp(3)) {
                        return@setOnTouchListener false
                    }

                    val isLeft = corner == CORNER_TL || corner == CORNER_BL
                    val isTop = corner == CORNER_TL || corner == CORNER_TR

                    val bounds = overlayBounds()
                    val minWinW = dp(MIN_PANEL_WIDTH_DP)
                    val minWinH = dp(MIN_PANEL_HEIGHT_DP)
                    val maxWinW = bounds.x
                    val maxWinH = bounds.y

                    var l = l0
                    var t = t0
                    var r = r0
                    var b = b0
                    // 只移动被拖的那两条边；对角的边完全不动
                    if (isLeft) {
                        l = (l0 + dx).coerceAtMost(r0 - minWinW).coerceAtLeast(0)
                    } else {
                        r = (r0 + dx).coerceAtLeast(l0 + minWinW).coerceAtMost(bounds.x)
                    }
                    if (isTop) {
                        t = (t0 + dy).coerceAtMost(b0 - minWinH).coerceAtLeast(0)
                    } else {
                        b = (b0 + dy).coerceAtLeast(t0 + minWinH).coerceAtMost(bounds.y)
                    }

                    var w = r - l
                    var h = b - t
                    // 超过最大尺寸时，收回「被拖动的那条边」，锚定边仍不动
                    if (w > maxWinW) {
                        w = maxWinW
                        if (isLeft) l = r - w else r = l + w
                    }
                    if (h > maxWinH) {
                        h = maxWinH
                        if (isTop) t = b - h else b = t + h
                    }

                    panelParams.width = w
                    panelParams.height = h
                    panelParams.x = l
                    panelParams.y = t
                    runCatching { windowManager.updateViewLayout(panel!!.root, panelParams) }
                    true
                }
                else -> false
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** 初始化答案区 WebView：加载离线模板（内嵌 KaTeX + marked），开启 JS 以便流式重绘。 */
    private fun setupWebView(web: WebView) {
        web.setBackgroundColor(Color.TRANSPARENT)
        web.isVerticalScrollBarEnabled = true
        web.settings.javaScriptEnabled = true
        // 允许长按选中正文并调用系统复制菜单（配合顶部「复制」，覆盖片段/整段复制）
        web.isLongClickable = true
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

    /** 打开大面板：显示面板并隐藏悬浮球（避免两层叠在一起、也缩小截屏需遮挡的面积）。 */
    private fun showPanel(): FloatPanelBinding {
        val binding = ensurePanel()
        binding.root.visibility = View.VISIBLE
        ball?.root?.visibility = View.GONE
        return binding
    }

    /** 关闭面板：隐藏面板并恢复悬浮球。 */
    private fun hidePanel() {
        // 先清掉输入框焦点 → 触发失焦回调恢复「非获焦」，把返回手势交还系统
        panel?.etRequest?.clearFocus()
        panel?.root?.visibility = View.GONE
        ball?.root?.visibility = View.VISIBLE
    }

    // ---------------------------------------------------------------- 模式

    /** 依当前模式刷新面板与悬浮球的文案、可见性与可获焦状态（切模式与建面板时调用）。 */
    private fun applyMode() {
        val mode = Prefs.mode(this)
        val daily = mode == Prefs.MODE_DAILY
        val realtime = mode == Prefs.MODE_REALTIME

        ball?.ball?.text = getString(
            when (mode) {
                Prefs.MODE_DAILY -> R.string.ball_text_daily
                Prefs.MODE_REALTIME -> R.string.ball_text_live
                else -> R.string.ball_text
            }
        )

        val p = panel ?: return
        // 解题模式：题目区 + 翻动拼接；日常/实时模式：需求输入行（文字/语音）
        p.zoneQuestion.visibility = if (daily || realtime) View.GONE else View.VISIBLE
        p.dailyInputBar.visibility = if (daily || realtime) View.VISIBLE else View.GONE
        p.btnStitch.visibility = if (daily || realtime) View.GONE else View.VISIBLE
        // 实时模式无需「截屏」：屏幕一有变化就会自动回答
        p.btnDailyShot.visibility = if (realtime) View.GONE else View.VISIBLE
        if (!daily && !realtime) {
            p.etRequest.setText("")
            p.etRequest.clearFocus()
        }
        p.tvTitle.text = getString(
            when (mode) {
                Prefs.MODE_DAILY -> R.string.panel_title_daily
                Prefs.MODE_REALTIME -> R.string.panel_title_realtime
                else -> R.string.panel_title
            }
        )
        p.tvAnswerLabel.text = getString(
            when (mode) {
                Prefs.MODE_DAILY -> R.string.panel_answer_label_daily
                Prefs.MODE_REALTIME -> R.string.panel_answer_label_realtime
                else -> R.string.panel_answer_label
            }
        )
        updateRealtimeButton()
        styleSeg(p.segSolve, mode == Prefs.MODE_SOLVE)
        styleSeg(p.segDaily, daily)
        styleSeg(p.segRealtime, realtime)
        // 关键：面板默认保持「非获焦」，否则可获焦的 overlay 会成为返回手势的目标，
        // 导致全面屏边缘向内划返回被屏蔽。仅在输入框获得焦点时才临时可获焦（见 etRequest 回调）。
        setPanelFocusable(false)
    }

    /** 主按钮文案随模式变化：解题=重新解答，日常=发送，实时=开始/暂停实时。 */
    private fun updateRealtimeButton() {
        val p = panel ?: return
        p.btnReread.text = getString(
            when (Prefs.mode(this)) {
                Prefs.MODE_DAILY -> R.string.btn_send
                Prefs.MODE_REALTIME ->
                    if (realtimeActive) R.string.btn_realtime_stop else R.string.btn_realtime_start
                else -> R.string.btn_reread
            }
        )
    }

    private fun setMode(mode: String) {
        if (Prefs.mode(this) == mode) return
        // 离开实时模式前先停掉采集循环
        if (Prefs.mode(this) == Prefs.MODE_REALTIME) stopRealtime()
        Prefs.setMode(this, mode)
        lastFingerprint = null
        answerBuffer.setLength(0)
        renderAnswer("")
        panel?.tvStatus?.text = getString(
            when (mode) {
                Prefs.MODE_DAILY -> R.string.panel_status_idle_daily
                Prefs.MODE_REALTIME -> R.string.panel_status_realtime_idle
                else -> R.string.panel_status_idle
            }
        )
        applyMode()
        if (mode == Prefs.MODE_REALTIME && panel?.root?.visibility == View.VISIBLE) {
            startRealtime()
        }
    }

    private fun styleSeg(view: TextView, selected: Boolean) {
        view.setBackgroundResource(
            if (selected) R.drawable.bg_segment_selected else R.drawable.bg_segment_default
        )
        view.setTextColor(ContextCompat.getColor(this, if (selected) R.color.on_primary else R.color.steel))
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

    /** 日常模式直接截屏：不带需求，按当前屏幕让千问给指引。 */
    private fun sendDailyShot() {
        if (busy) return
        stopVoice()
        hideKeyboard()
        panel?.etRequest?.setText("")
        doCapture("")
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
     * 截屏并进入后续流程。面板保持可见，不闪屏；悬浮层（悬浮球 + 面板）区域在截图上涂白遮挡。
     * @param request 为 null 走解题模式（读题 → 答题）；非 null 走日常模式（截图+需求 → 直接给指引）。
     */
    private fun doCapture(request: String? = null) {
        if (busy) return
        cancelStream()
        stopVoice()
        busy = true
        val daily = request != null

        // 面板保持可见（同时隐藏悬浮球），截屏不再隐藏悬浮层，避免画面闪烁
        val p = showPanel()
        p.tvStatus.text = getString(R.string.panel_status_capturing)
        answerBuffer.setLength(0)
        renderAnswer("")
        if (!daily) p.tvQuestion.text = getString(R.string.panel_question_empty)

        Thread {
            val bitmap = capture?.capture()
            if (bitmap == null) {
                handler.post {
                    p.tvStatus.text = getString(R.string.panel_status_capture_fail)
                    busy = false
                }
                return@Thread
            }
            // 在截图上把悬浮层区域涂白，等效于「看不见悬浮窗」，但屏幕本身不闪
            maskOverlays(bitmap)
            val base64 = bitmapToJpegBase64(bitmap)
            bitmap.recycle()
            handler.post {
                if (daily) startDaily(base64, request.orEmpty()) else startPipeline(listOf(base64))
            }
        }.start()
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
    private fun startPipeline(images: List<String>) {
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
        readQuestion(images)
    }

    /** 第一段：千问视觉读题，把（可能多张的）截图转成题目文字（含 LaTeX）。 */
    private fun readQuestion(images: List<String>) {
        val key = Prefs.apiKey(this, Prefs.PROVIDER_QWEN)
        val c = ChatClient(key, Prefs.MODEL_QWEN_VISION, ChatClient.QWEN_URL)
        client = c

        val sb = StringBuilder()
        Thread {
            c.streamImages(
                imageBase64List = images,
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

    // ---------------------------------------------------------------- 实时模式

    /**
     * 开始实时：开启采集循环，持续观察屏幕。
     * 有常驻的文字/语音需求时一并带上；屏幕无变化则跳过，避免无效请求。
     */
    private fun startRealtime() {
        if (realtimeActive) return
        if (capture == null) {
            panel?.tvStatus?.text = getString(R.string.panel_status_capture_fail)
            return
        }
        if (Prefs.apiKey(this, Prefs.PROVIDER_QWEN).isBlank()) {
            panel?.tvStatus?.text = getString(R.string.status_need_qwen_key)
            return
        }
        realtimeActive = true
        realtimeBusy = false
        lastFingerprint = null
        panel?.tvStatus?.text = getString(R.string.panel_status_realtime_idle)
        updateRealtimeButton()
        scheduleRealtime()
    }

    /** 暂停实时：停止采集循环并清空指纹；已完成渲染的答案保留在面板上。 */
    private fun stopRealtime() {
        handler.removeCallbacks(realtimeTick)
        realtimeBusy = false
        if (!realtimeActive) {
            updateRealtimeButton()
            return
        }
        realtimeActive = false
        lastFingerprint = null
        cancelStream()
        panel?.tvStatus?.text = getString(R.string.panel_status_realtime_paused)
        updateRealtimeButton()
    }

    private fun toggleRealtime() {
        if (realtimeActive) stopRealtime() else startRealtime()
    }

    private fun scheduleRealtime() {
        handler.removeCallbacks(realtimeTick)
        handler.postDelayed(realtimeTick, REALTIME_INTERVAL_MS)
    }

    /**
     * 采集一帧：后台截屏 → 涂白悬浮层 → 与上一帧指纹比对。
     * 画面明显变化才交千问即时回答；无变化则直接安排下一轮。请求期间不重入。
     */
    private fun realtimeGrab() {
        if (!realtimeActive) return
        val cap = capture
        if (cap == null) {
            stopRealtime()
            return
        }
        if (realtimeBusy) {
            scheduleRealtime()
            return
        }
        realtimeBusy = true
        Thread {
            val bitmap = cap.capture()
            var base64: String? = null
            if (bitmap != null) {
                maskOverlays(bitmap)
                val fp = fingerprint(bitmap)
                val prev = lastFingerprint
                if (prev == null || !similar(prev, fp)) {
                    lastFingerprint = fp
                    base64 = bitmapToJpegBase64(bitmap)
                }
                bitmap.recycle()
            }
            handler.post {
                realtimeBusy = false
                if (!realtimeActive) return@post
                val b64 = base64
                if (b64 != null) startRealtimeAsk(b64) else scheduleRealtime()
            }
        }.start()
    }

    /** 把当前帧与常驻需求交千问即时回答（单段，不经读题 → 答题）。完成后安排下一轮。 */
    private fun startRealtimeAsk(imageBase64: String) {
        if (!realtimeActive) return
        val key = Prefs.apiKey(this, Prefs.PROVIDER_QWEN)
        if (key.isBlank()) {
            panel?.tvStatus?.text = getString(R.string.status_need_qwen_key)
            stopRealtime()
            return
        }
        val request = panel?.etRequest?.text?.toString().orEmpty()
        cancelStream()
        answerBuffer.setLength(0)
        renderAnswer("")
        panel?.tvStatus?.text = getString(R.string.panel_status_realtime)

        val c = ChatClient(key, Prefs.MODEL_QWEN_VISION, ChatClient.QWEN_URL)
        client = c
        Thread {
            c.streamImageAsk(
                imageBase64 = imageBase64,
                request = request,
                onDelta = { delta -> handleDelta(delta) },
                onDone = {
                    handler.post {
                        finishStream()
                        if (realtimeActive) scheduleRealtime()
                    }
                },
                onError = { msg ->
                    handler.post {
                        panel?.tvStatus?.text = getString(R.string.panel_status_error) + msg
                        if (realtimeActive) scheduleRealtime()
                    }
                },
                prompt = ChatClient.REALTIME_PROMPT
            )
        }.start()
    }

    /** 16×16 灰度指纹：缩放后取每点亮度，用于快速判断画面是否变化。 */
    private fun fingerprint(bitmap: Bitmap): IntArray {
        val small = Bitmap.createScaledBitmap(bitmap, FP_SIZE, FP_SIZE, true)
        val pixels = IntArray(FP_SIZE * FP_SIZE)
        small.getPixels(pixels, 0, FP_SIZE, 0, 0, FP_SIZE, FP_SIZE)
        if (small !== bitmap) small.recycle()
        return IntArray(pixels.size) { i ->
            val c = pixels[i]
            (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
        }
    }

    /** 两帧指纹的平均绝对差低于阈值即视为「画面未变化」。 */
    private fun similar(a: IntArray, b: IntArray): Boolean {
        if (a.size != b.size || a.isEmpty()) return false
        var sum = 0
        for (i in a.indices) sum += abs(a[i] - b[i])
        return sum / a.size < REALTIME_DIFF_THRESHOLD
    }

    // ---------------------------------------------------------------- 翻动拼接识别（解题模式）

    /**
     * 开始翻动拼接：进入连续截屏采集，逐帧去重叠累积新增片段。
     * 采集期间面板收起、悬浮球文案变为「停」，点悬浮球即结束并进入读题 → 答题。
     */
    private fun startStitch() {
        if (busy || stitching) return
        if (capture == null) {
            panel?.tvStatus?.text = getString(R.string.panel_status_capture_fail)
            return
        }
        cancelStream()
        stopVoice()
        answerBuffer.setLength(0)
        renderAnswer("")
        stitchSlices.clear()
        stitcher = ScrollStitcher()
        stitching = true

        panel?.root?.visibility = View.GONE
        ball?.ball?.text = getString(R.string.ball_text_stop)
        toast(getString(R.string.stitch_hint))
        handler.postDelayed(stitchTick, STITCH_INTERVAL_MS)
    }

    /** 结束拼接：复原悬浮层，把累积的切片整体交给读题 → 答题流水线。 */
    private fun stopStitch() {
        if (!stitching) return
        stitching = false
        handler.removeCallbacks(stitchTick)
        stitcher = null
        applyMode() // 复原悬浮球文案与面板形态

        val slices = stitchSlices.toList()
        stitchSlices.clear()
        showPanel()
        if (slices.isEmpty()) {
            panel?.tvStatus?.text = getString(R.string.stitch_empty)
            return
        }
        startPipeline(slices)
    }

    /**
     * 采集一帧：后台线程截屏 → 去重叠得到新增切片 → 编码为 JPEG 累积；随后安排下一帧。
     * 悬浮球在采集期间保持可见（作为「停止」按钮），其所在区域在切片中被涂白遮挡。
     */
    private fun grabForStitch() {
        if (!stitching) return
        val cap = capture
        if (cap == null) {
            stopStitch()
            return
        }
        val mask = ballMaskRect()
        Thread {
            val frame = cap.capture()
            var slice: Bitmap? = null
            if (frame != null) {
                slice = runCatching { stitcher?.feed(frame, mask) }.getOrNull()
                frame.recycle()
            }
            val jpeg = slice?.let {
                val s = bitmapToJpegBase64(it)
                it.recycle()
                s
            }
            handler.post {
                if (!stitching) return@post
                if (jpeg != null) {
                    stitchSlices.add(jpeg)
                    if (stitchSlices.size >= MAX_STITCH_SLICES) {
                        toast(getString(R.string.stitch_full))
                        stopStitch()
                        return@post
                    }
                }
                handler.postDelayed(stitchTick, STITCH_INTERVAL_MS)
            }
        }.start()
    }

    /** 悬浮球在屏幕截图中的外接矩形（含少量外扩），用于在切片中遮挡掉悬浮球本身。 */
    private fun ballMaskRect(): Rect? {
        if (!::ballParams.isInitialized) return null
        if (ball?.root?.visibility != View.VISIBLE) return null
        // 悬浮层窗口从状态栏下沿起算，截图则是整屏，故需补上状态栏高度
        val inset = topInsetPx()
        val pad = dp(4)
        return Rect(
            ballParams.x - pad,
            ballParams.y - pad + inset,
            ballParams.x + ballParams.width + pad,
            ballParams.y + ballParams.height + pad + inset
        )
    }

    /** 面板在屏幕截图中的外接矩形；面板不可见时返回 null（同样需补状态栏高度）。 */
    private fun panelMaskRect(): Rect? {
        if (!::panelParams.isInitialized) return null
        if (panel?.root?.visibility != View.VISIBLE) return null
        val inset = topInsetPx()
        return Rect(
            panelParams.x,
            panelParams.y + inset,
            panelParams.x + panelParams.width,
            panelParams.y + panelParams.height + inset
        )
    }

    /** 需要在截图中遮挡的全部悬浮层区域（可见的悬浮球与面板）。 */
    private fun overlayMaskRects(): List<Rect> =
        listOfNotNull(ballMaskRect(), panelMaskRect())

    /** 把悬浮层区域在截图上涂白：截屏不必隐藏悬浮窗，画面也不会闪。 */
    private fun maskOverlays(bitmap: Bitmap) {
        val rects = overlayMaskRects()
        if (rects.isEmpty()) return
        val pad = dp(2)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        for (r in rects) {
            canvas.drawRect(
                (r.left - pad).toFloat(),
                (r.top - pad).toFloat(),
                (r.right + pad).toFloat(),
                (r.bottom + pad).toFloat(),
                paint
            )
        }
    }

    private fun topInsetPx(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                windowManager.currentWindowMetrics.windowInsets
                    .getInsets(android.view.WindowInsets.Type.systemBars()).top
            }.getOrDefault(0)
        } else {
            0
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
        Prefs.unregisterModeListener(this, modeListener)
        realtimeActive = false
        realtimeBusy = false
        stitching = false
        stitcher = null
        stitchSlices.clear()
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
        private const val BALL_SIZE_DP = 56
        private const val BALL_MARGIN_DP = 8
        private const val PANEL_WIDTH_DP = 320
        private const val PANEL_HEIGHT_DP = 560

        /** 四角缩放时可缩到的最小面板尺寸（dp） */
        private const val MIN_PANEL_WIDTH_DP = 240
        private const val MIN_PANEL_HEIGHT_DP = 320

        /** 四角缩放热区对应的四个角 */
        private const val CORNER_TL = 0
        private const val CORNER_TR = 1
        private const val CORNER_BL = 2
        private const val CORNER_BR = 3

        /** 流式渲染节流间隔（毫秒） */
        private const val RENDER_THROTTLE_MS = 120L

        /** 翻动拼接：相邻两帧的采集间隔（毫秒），给用户留出滚动时间 */
        private const val STITCH_INTERVAL_MS = 600L

        /** 翻动拼接：最多累积的片段数，防止内存与请求体过大 */
        private const val MAX_STITCH_SLICES = 12

        /** 实时模式：两次采集之间的间隔（毫秒） */
        private const val REALTIME_INTERVAL_MS = 3000L

        /** 实时模式：画面指纹平均绝对差低于该值即视为「未变化」，跳过请求 */
        private const val REALTIME_DIFF_THRESHOLD = 8

        /** 实时模式：画面指纹缩放边长（像素），16×16 足以判断整体变化 */
        private const val FP_SIZE = 16

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
