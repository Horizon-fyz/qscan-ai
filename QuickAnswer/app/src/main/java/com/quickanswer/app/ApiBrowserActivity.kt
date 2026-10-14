package com.quickanswer.app

import android.annotation.SuppressLint
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
import androidx.appcompat.app.AppCompatActivity
import com.quickanswer.app.databinding.ActivityApiBrowserBinding
import com.quickanswer.app.databinding.ItemApiVendorBinding

/**
 * 开放平台页：等价于「内置浏览器」，但只放行各模型厂商的开放平台 / 控制台域名，
 * 用于申请与管理 API Key；其它站点一律拦截，避免被当作通用浏览器使用。
 *
 * 交互：先展示厂商列表；点选任一厂商在 WebView 中打开其控制台，顶栏「厂商」可随时退回列表。
 */
class ApiBrowserActivity : AppCompatActivity() {

    private lateinit var binding: ActivityApiBrowserBinding

    /** 单个厂商：展示名、说明、入口地址与放行域名（含其子域）。 */
    private data class Vendor(
        val name: String,
        val desc: String,
        val url: String,
        val hosts: List<String>
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityApiBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        buildVendorList()
        setupWebView()

        binding.btnBack.setOnClickListener { navigateBack() }
        binding.tvPortal.setOnClickListener { showPortal() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = navigateBack()
        })
    }

    // ------------------------------------------------------------ 厂商目录

    private fun buildVendorList() {
        val inflater = LayoutInflater.from(this)
        VENDORS.forEach { vendor ->
            val row = ItemApiVendorBinding.inflate(inflater, binding.listContainer, false)
            row.tvName.text = vendor.name
            row.tvDesc.text = vendor.desc
            row.root.setOnClickListener { openVendor(vendor) }
            binding.listContainer.addView(row.root)
        }
    }

    private fun openVendor(vendor: Vendor) {
        binding.tvPortal.visibility = View.VISIBLE
        binding.tvTitle.text = vendor.name
        binding.scrollList.visibility = View.GONE
        binding.webView.visibility = View.VISIBLE
        binding.webView.loadUrl(vendor.url)
    }

    /** 退回厂商列表；WebView 保留已加载页面，便于再次进入。 */
    private fun showPortal() {
        binding.webView.visibility = View.GONE
        binding.scrollList.visibility = View.VISIBLE
        binding.tvPortal.visibility = View.GONE
        binding.tvTitle.text = getString(R.string.browser_title)
    }

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
                if ((scheme == "http" || scheme == "https") && isAllowed(url.host)) {
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
                if (isAllowed(Uri.parse(href).host)) {
                    view.loadUrl(href)
                } else {
                    blockNavigation()
                }
                return true
            }
        }
    }

    /** 域名白名单：命中自身或任意子域才放行。 */
    private fun isAllowed(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val h = host.lowercase()
        return ALLOWED_HOSTS.any { h == it || h.endsWith(".$it") }
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

    private companion object {
        /** 各大模型厂商开放平台入口；hosts 为放行域名（含登录 / 静态资源等必需子域）。 */
        val VENDORS = listOf(
            Vendor(
                name = "阿里云百炼（通义千问）",
                desc = "通义千问 / 百炼 API Key 申请与管理",
                url = "https://bailian.console.aliyun.com/",
                hosts = listOf(
                    "aliyun.com", "aliyun-inc.com", "aliyuncs.com",
                    "alibabacloud.com", "alibaba.com", "alicdn.com",
                    "alipay.com", "alipayobjects.com",
                    "taobao.com", "tmall.com", "mmstat.com"
                )
            ),
            Vendor(
                name = "DeepSeek 开放平台",
                desc = "deepseek-chat / reasoner API Key 申请",
                url = "https://platform.deepseek.com/",
                hosts = listOf("deepseek.com")
            ),
            Vendor(
                name = "智谱 AI 开放平台",
                desc = "GLM 系列模型 API Key 申请",
                url = "https://bigmodel.cn/",
                hosts = listOf("bigmodel.cn", "zhipuai.cn")
            ),
            Vendor(
                name = "月之暗面 Kimi 开放平台",
                desc = "Kimi 大模型 API Key 申请",
                url = "https://platform.moonshot.cn/",
                hosts = listOf("moonshot.cn", "moonshot.com")
            ),
            Vendor(
                name = "硅基流动 SiliconFlow",
                desc = "多模型聚合平台 API Key 申请",
                url = "https://cloud.siliconflow.cn/",
                hosts = listOf("siliconflow.cn", "siliconflow.com")
            ),
            Vendor(
                name = "火山方舟（豆包大模型）",
                desc = "豆包 / 方舟 API Key 申请",
                url = "https://console.volcengine.com/ark",
                hosts = listOf("volcengine.com", "volces.com")
            ),
            Vendor(
                name = "MiniMax 开放平台",
                desc = "MiniMax 大模型 API Key 申请",
                url = "https://platform.minimaxi.com/",
                hosts = listOf("minimaxi.com", "minimax.io", "minimax.com")
            ),
            Vendor(
                name = "OpenAI Platform",
                desc = "GPT 系列 API Key 申请（需可访问国际网络）",
                url = "https://platform.openai.com/api-keys",
                hosts = listOf("openai.com", "oaistatic.com", "oaiusercontent.com", "auth0.com")
            )
        )

        val ALLOWED_HOSTS = VENDORS.flatMap { it.hosts }.toSet()
    }
}
