package com.quickanswer.app

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.view.LayoutInflater
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.quickanswer.app.databinding.ActivityApiBrowserBinding
import com.quickanswer.app.databinding.DialogSaveApiKeyBinding
import com.quickanswer.app.databinding.ItemApiVendorBinding
import com.quickanswer.app.databinding.ItemSavedKeyBinding

/**
 * 开放平台页：等价于「内置浏览器」，但只放行各模型厂商的开放平台 / 控制台域名，
 * 用于申请与管理 API Key；其它站点一律拦截，避免被当作通用浏览器使用。
 *
 * 交互：先展示厂商列表；点选任一厂商在 WebView 中打开其控制台，顶栏「厂商」可随时退回列表。
 *
 * 剪贴板识别为「持续监听」：由 [ClipboardWatcher] 在进程级监听，本页在前台时自动接收识别结果，
 * 复制到的 Key 会直接弹出保存对话框；保留顶栏按钮作为手动补识别入口。
 */
class ApiBrowserActivity : AppCompatActivity(), ClipboardWatcher.Listener {

    private lateinit var binding: ActivityApiBrowserBinding

    /** 最近打开的厂商：作为剪贴板来源识别的依据。 */
    private var currentVendor: Vendor? = null

    /** 保存对话框是否正在显示：避免持续监听时重复叠加弹窗。 */
    private var dialogShowing = false

    override fun onStart() {
        super.onStart()
        ClipboardWatcher.addListener(this)
    }

    override fun onStop() {
        ClipboardWatcher.removeListener(this)
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityApiBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        buildVendorList()
        setupWebView()

        binding.btnBack.setOnClickListener { navigateBack() }
        binding.tvPortal.setOnClickListener { showPortal() }
        binding.btnScan.setOnClickListener { scanClipboard() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = navigateBack()
        })

        refreshSaved()
    }

    // ------------------------------------------------------------ 厂商目录

    private fun buildVendorList() {
        val inflater = LayoutInflater.from(this)
        Vendors.LIST.forEach { vendor ->
            val row = ItemApiVendorBinding.inflate(inflater, binding.listContainer, false)
            row.tvName.text = vendor.name
            row.tvDesc.text = vendor.desc
            row.root.setOnClickListener { openVendor(vendor) }
            binding.listContainer.addView(row.root)
        }
    }

    private fun openVendor(vendor: Vendor) {
        currentVendor = vendor
        binding.tvPortal.visibility = View.VISIBLE
        binding.tvTitle.text = vendor.name
        binding.scrollList.visibility = View.GONE
        binding.webView.visibility = View.VISIBLE
        binding.webView.loadUrl(vendor.consoleUrl)
    }

    /** 退回厂商列表；WebView 保留已加载页面，便于再次进入。 */
    private fun showPortal() {
        binding.webView.visibility = View.GONE
        binding.scrollList.visibility = View.VISIBLE
        binding.tvPortal.visibility = View.GONE
        binding.tvTitle.text = getString(R.string.browser_title)
    }

    // ------------------------------------------------------------ 剪贴板 / 来源识别

    /** 读取剪贴板 → 识别来源厂商 → 保存该 API。 */
    private fun scanClipboard() {
        val text = readClipboard()?.trim().orEmpty()
        if (text.isBlank()) {
            toast(getString(R.string.browser_clip_empty))
            return
        }
        // 1) 复制的是厂商链接：来源识别，直接打开对应控制台。
        val byUrl = Vendors.matchByUrlText(text)
        if (byUrl != null && !Vendors.looksLikeKey(text)) {
            toast(getString(R.string.browser_clip_url, byUrl.name))
            openVendor(byUrl)
            return
        }
        // 2) 像 API Key：优先用当前浏览的厂商（网页复制来源），其次依前缀推断。
        if (Vendors.looksLikeKey(text)) {
            val vendor = currentVendor ?: Vendors.matchByKey(text)
            if (vendor != null) toast(getString(R.string.browser_clip_recognized, vendor.name))
            showSaveDialog(text, vendor)
            return
        }
        toast(getString(R.string.browser_clip_none))
    }

    private fun readClipboard(): String? {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(this)?.toString()
    }

    // [ClipboardWatcher.Listener] 持续监听回调：本页在前台时自动处理识别结果。

    /** 识别到 API Key：自动弹出保存对话框；来源优先取当前浏览的厂商。 */
    override fun onApiKey(text: String, vendor: Vendor?) {
        if (isFinishing || isDestroyed || dialogShowing) return
        val source = vendor ?: currentVendor
        if (source != null) toast(getString(R.string.browser_clip_recognized, source.name))
        showSaveDialog(text, source)
    }

    /** 识别到厂商控制台链接：仅提示，不自动跳转，避免打断当前操作。 */
    override fun onVendorLink(vendor: Vendor) {
        if (isFinishing || isDestroyed) return
        toast(getString(R.string.browser_clip_url_hint, vendor.name))
    }

    // ------------------------------------------------------------ 保存对话框

    /** [vendor] 为识别到的厂商；为空表示交由用户选择厂商或改填自定义请求地址。 */
    private fun showSaveDialog(apiKey: String, vendor: Vendor?) {
        val b = DialogSaveApiKeyBinding.inflate(layoutInflater)
        b.etKey.setText(apiKey)

        var selected: Vendor? = null
        var custom = false

        fun applySelection(v: Vendor?, isCustom: Boolean) {
            selected = v
            custom = isCustom
            when {
                isCustom -> {
                    b.tvVendorName.text = getString(R.string.key_custom_vendor)
                    b.groupCustom.visibility = View.VISIBLE
                    b.tvVendorNote.text = getString(R.string.key_note_custom)
                    if (b.etBaseUrl.text.isNullOrBlank()) b.etBaseUrl.setText(Prefs.DEFAULT_TEXT_BASE_URL)
                }
                v != null -> {
                    b.tvVendorName.text = v.name
                    b.groupCustom.visibility = View.GONE
                    b.tvVendorNote.text = if (v.baseUrl.isNotBlank()) {
                        getString(R.string.key_note_official, v.baseUrl, v.defaultModel)
                    } else {
                        getString(R.string.key_note_no_api)
                    }
                }
                else -> {
                    b.tvVendorName.text = getString(R.string.key_pick_placeholder)
                    b.groupCustom.visibility = View.GONE
                    b.tvVendorNote.text = getString(R.string.key_note_pick)
                }
            }
        }

        b.rowVendor.setOnClickListener {
            val labels = Vendors.LIST.map { it.name } + getString(R.string.key_custom_vendor)
            val checked = when {
                custom -> labels.size - 1
                selected != null -> Vendors.LIST.indexOf(selected)
                else -> -1
            }
            AlertDialog.Builder(this)
                .setTitle(R.string.key_field_vendor)
                .setSingleChoiceItems(labels.toTypedArray(), checked) { d, which ->
                    if (which == labels.size - 1) applySelection(null, true)
                    else applySelection(Vendors.LIST[which], false)
                    d.dismiss()
                }
                .setNegativeButton(R.string.browser_cancel, null)
                .show()
        }

        applySelection(vendor, false)

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.key_dialog_title)
            .setView(b.root)
            .setPositiveButton(R.string.browser_save, null)
            .setNegativeButton(R.string.browser_cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (saveEntry(b, selected, custom)) dialog.dismiss()
            }
        }
        dialog.setOnDismissListener { dialogShowing = false }
        dialogShowing = true
        dialog.show()
    }

    /** 保存到密钥库，并把命中内置通道的 Key 回写到 [Prefs]；返回是否保存成功。 */
    private fun saveEntry(
        b: DialogSaveApiKeyBinding,
        vendor: Vendor?,
        custom: Boolean
    ): Boolean {
        val key = b.etKey.text.toString().trim()
        if (key.isBlank()) {
            toast(getString(R.string.key_need_key))
            return false
        }

        val entry: ApiKeys.Entry
        if (custom) {
            val base = b.etBaseUrl.text.toString().trim()
            if (base.isBlank()) {
                toast(getString(R.string.key_need_base))
                return false
            }
            val model = b.etModel.text.toString().trim()
            entry = ApiKeys.Entry(
                vendorId = ApiKeys.CUSTOM_ID,
                name = getString(R.string.key_custom_vendor),
                baseUrl = base, apiKey = key, custom = true, model = model,
                savedAt = System.currentTimeMillis()
            )
            // 自定义地址即作为答题通道接入。
            Prefs.setApiKey(this, Prefs.PROVIDER_DEEPSEEK, key)
            Prefs.setTextCustom(this, true)
            Prefs.setTextBaseUrl(this, base)
            if (model.isNotBlank()) Prefs.setTextModel(this, model)
        } else if (vendor != null) {
            entry = ApiKeys.Entry(
                vendorId = vendor.id,
                name = vendor.name,
                baseUrl = vendor.baseUrl, apiKey = key, custom = false,
                model = vendor.defaultModel, savedAt = System.currentTimeMillis()
            )
            when (vendor.provider) {
                Prefs.PROVIDER_QWEN -> Prefs.setApiKey(this, Prefs.PROVIDER_QWEN, key)
                Prefs.PROVIDER_DEEPSEEK -> {
                    Prefs.setApiKey(this, Prefs.PROVIDER_DEEPSEEK, key)
                    Prefs.setTextCustom(this, false)
                }
                else -> {
                    // 第三方厂商官方地址（OpenAI 兼容）：接入答题通道，官方地址开箱即用。
                    Prefs.setApiKey(this, Prefs.PROVIDER_DEEPSEEK, key)
                    Prefs.setTextCustom(this, true)
                    Prefs.setTextBaseUrl(this, vendor.baseUrl)
                    if (vendor.defaultModel.isNotBlank()) {
                        Prefs.setTextModel(this, vendor.defaultModel)
                    }
                }
            }
        } else {
            toast(getString(R.string.key_need_vendor))
            return false
        }

        ApiKeys.upsert(this, entry)
        refreshSaved()
        toast(getString(R.string.key_saved))
        return true
    }

    // ------------------------------------------------------------ 密钥库列表

    private fun refreshSaved() {
        binding.savedContainer.removeAllViews()
        val list = ApiKeys.all(this)
        binding.tvSavedEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE

        val inflater = LayoutInflater.from(this)
        list.forEach { entry ->
            val row = ItemSavedKeyBinding.inflate(inflater, binding.savedContainer, false)
            row.tvName.text = entry.name
            val masked = mask(entry.apiKey)
            row.tvKey.text = if (entry.custom) "$masked · ${entry.baseUrl}" else masked
            row.btnDel.setOnClickListener {
                ApiKeys.remove(this, entry.vendorId)
                refreshSaved()
                toast(getString(R.string.key_removed))
            }
            binding.savedContainer.addView(row.root)
        }
    }

    private fun mask(key: String): String =
        if (key.length <= 10) "••••••" else key.take(6) + "••••" + key.takeLast(4)

    // ------------------------------------------------------------ 浏览器

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        with(binding.webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(false)
        }
        // 厂商控制台登录普遍依赖跨站 Cookie（SSO），需显式放行第三方 Cookie。
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(binding.webView, true)
        }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url
                val scheme = url.scheme?.lowercase()
                if ((scheme == "http" || scheme == "https") && Vendors.isAllowed(url.host)) {
                    return false
                }
                blockNavigation()
                return true
            }

            override fun onPageFinished(view: WebView, url: String?) {
                binding.progress.visibility = View.GONE
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    binding.progress.visibility = View.GONE
                    toast(getString(R.string.browser_load_failed))
                }
            }
        }

        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                binding.progress.visibility =
                    if (newProgress in 1..99) View.VISIBLE else View.GONE
                binding.progress.progress = newProgress
            }

            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message
            ): Boolean {
                // target=_blank 等新窗口：在同一 WebView 内打开，并沿用白名单校验。
                val href = view.hitTestResult.extra
                if (href.isNullOrBlank()) return false
                if (Vendors.isAllowed(Uri.parse(href).host)) {
                    view.loadUrl(href)
                } else {
                    blockNavigation()
                }
                return true
            }
        }
    }

    private fun blockNavigation() = toast(getString(R.string.browser_blocked))

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun navigateBack() {
        if (binding.webView.visibility == View.VISIBLE) {
            if (binding.webView.canGoBack()) binding.webView.goBack() else showPortal()
        } else {
            finish()
        }
    }

    override fun onDestroy() {
        binding.webView.stopLoading()
        binding.webView.destroy()
        super.onDestroy()
    }
}
