package com.quickanswer.app

/**
 * 单个模型厂商：开放平台入口、放行域名，以及 OpenAI 兼容的请求地址 / 默认模型 / Key 特征。
 *
 * 供「开放平台」内置浏览器与剪贴板 Key 识别共用一份目录。
 */
data class Vendor(
    /** 稳定标识，用作密钥库主键。 */
    val id: String,
    val name: String,
    val desc: String,
    /** 控制台 / 开放平台入口。 */
    val consoleUrl: String,
    /** 放行域名（含登录 / 静态资源等必需子域）。 */
    val hosts: List<String>,
    /** 官方 OpenAI 兼容 base 地址；空串表示不提供答题接口。 */
    val baseUrl: String,
    /** 内置通道标识：[Prefs.PROVIDER_QWEN] / [Prefs.PROVIDER_DEEPSEEK]；其余为 null（第三方兼容）。 */
    val provider: String?,
    /** 建议模型名，接入自定义通道时默认填充。 */
    val defaultModel: String,
    /** Key 常见前缀，用于剪贴板识别（不强判定）。 */
    val keyPrefixes: List<String>
)

/** 厂商目录与域名白名单。 */
object Vendors {

    val LIST: List<Vendor> = listOf(
        Vendor(
            id = "qwen",
            name = "阿里云百炼（通义千问）",
            desc = "通义千问 / 百炼 API Key 申请与管理",
            consoleUrl = "https://bailian.console.aliyun.com/",
            hosts = listOf(
                "aliyun.com", "aliyun-inc.com", "aliyuncs.com",
                "alibabacloud.com", "alibaba.com", "alicdn.com",
                "alipay.com", "alipayobjects.com",
                "taobao.com", "tmall.com", "mmstat.com"
            ),
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            provider = Prefs.PROVIDER_QWEN,
            defaultModel = Prefs.MODEL_QWEN_VISION,
            keyPrefixes = listOf("sk-")
        ),
        Vendor(
            id = "deepseek",
            name = "DeepSeek 开放平台",
            desc = "deepseek-chat / reasoner API Key 申请",
            consoleUrl = "https://platform.deepseek.com/",
            hosts = listOf("deepseek.com"),
            baseUrl = ChatClient.DEEPSEEK_BASE,
            provider = Prefs.PROVIDER_DEEPSEEK,
            defaultModel = Prefs.MODEL_CHAT,
            keyPrefixes = listOf("sk-")
        ),
        Vendor(
            id = "zhipu",
            name = "智谱 AI 开放平台",
            desc = "GLM 系列模型 API Key 申请",
            consoleUrl = "https://bigmodel.cn/",
            hosts = listOf("bigmodel.cn", "zhipuai.cn"),
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            provider = null,
            defaultModel = "glm-4-plus",
            keyPrefixes = listOf()
        ),
        Vendor(
            id = "moonshot",
            name = "月之暗面 Kimi 开放平台",
            desc = "Kimi 大模型 API Key 申请",
            consoleUrl = "https://platform.moonshot.cn/",
            hosts = listOf("moonshot.cn", "moonshot.com"),
            baseUrl = "https://api.moonshot.cn/v1",
            provider = null,
            defaultModel = "moonshot-v1-8k",
            keyPrefixes = listOf("sk-")
        ),
        Vendor(
            id = "siliconflow",
            name = "硅基流动 SiliconFlow",
            desc = "多模型聚合平台 API Key 申请",
            consoleUrl = "https://cloud.siliconflow.cn/",
            hosts = listOf("siliconflow.cn", "siliconflow.com"),
            baseUrl = "https://api.siliconflow.cn/v1",
            provider = null,
            defaultModel = "deepseek-ai/DeepSeek-V3",
            keyPrefixes = listOf("sk-")
        ),
        Vendor(
            id = "volc",
            name = "火山方舟（豆包大模型）",
            desc = "豆包 / 方舟 API Key 申请",
            consoleUrl = "https://console.volcengine.com/ark",
            hosts = listOf("volcengine.com", "volces.com"),
            baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
            provider = null,
            defaultModel = "doubao-pro-32k",
            keyPrefixes = listOf()
        ),
        Vendor(
            id = "minimax",
            name = "MiniMax 开放平台",
            desc = "MiniMax 大模型 API Key 申请",
            consoleUrl = "https://platform.minimaxi.com/",
            hosts = listOf("minimaxi.com", "minimax.io", "minimax.com"),
            baseUrl = "https://api.minimaxi.com/v1",
            provider = null,
            defaultModel = "abab6.5s-chat",
            keyPrefixes = listOf()
        ),
        Vendor(
            id = "openai",
            name = "OpenAI Platform",
            desc = "GPT 系列 API Key 申请（需可访问国际网络）",
            consoleUrl = "https://platform.openai.com/api-keys",
            hosts = listOf("openai.com", "oaistatic.com", "oaiusercontent.com", "auth0.com"),
            baseUrl = "https://api.openai.com/v1",
            provider = null,
            defaultModel = "gpt-4o-mini",
            keyPrefixes = listOf("sk-")
        )
    )

    /** 内置浏览器放行的全部域名。 */
    val ALLOWED_HOSTS: Set<String> = LIST.flatMap { it.hosts }.toSet()

    fun byId(id: String): Vendor? = LIST.firstOrNull { it.id == id }

    /** 域名白名单：命中自身或任意子域才放行。 */
    fun isAllowed(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val h = host.lowercase()
        return ALLOWED_HOSTS.any { h == it || h.endsWith(".$it") }
    }

    /** 依 URL 主机识别归属厂商（来源识别）。 */
    fun matchByHost(host: String?): Vendor? {
        if (host.isNullOrBlank()) return null
        val h = host.lowercase()
        return LIST.firstOrNull { v -> v.hosts.any { h == it || h.endsWith(".$it") } }
    }

    /** 从任意文本中提取 http(s) 链接并识别归属厂商。 */
    fun matchByUrlText(text: String): Vendor? {
        val url = Regex("""https?://[^\s"'<>）)】\]]+""").find(text)?.value ?: return null
        return matchByHost(android.net.Uri.parse(url).host)
    }

    /**
     * 依 Key 形态识别厂商：仅当某个前缀在所有厂商中唯一命中时才返回，避免误判。
     * 多数厂商共用 `sk-` 前缀，此时返回 null，交由用户确认。
     */
    fun matchByKey(key: String): Vendor? {
        val k = key.trim()
        if (k.isEmpty()) return null
        val hits = LIST.filter { v -> v.keyPrefixes.any { k.startsWith(it) } }
        return hits.singleOrNull()
    }

    /**
     * 文本是否像一把 API Key：单行、长度 ≥16、仅含 ASCII 字母数字与 -_. 、且不是 URL / 邮箱。
     *
     * 限定 ASCII 是为了持续监听时避免误判——中文等非 ASCII 文字会被 [Char.isLetterOrDigit]
     * 判为字母，导致普通句子被当成 Key。
     */
    fun looksLikeKey(text: String): Boolean {
        val t = text.trim()
        if (t.length < 16 || t.length > 256) return false
        if (t.any { it.isWhitespace() }) return false
        if (t.contains("://") || t.contains("@")) return false
        return t.all {
            it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' || it == '.'
        }
    }
}
