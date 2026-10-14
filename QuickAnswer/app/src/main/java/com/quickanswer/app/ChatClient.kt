package com.quickanswer.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * OpenAI 兼容的 chat/completions 客户端（SSE 流式），零第三方依赖。
 *
 * 两段式流水线：
 *  - [streamImage] 视觉读题（千问 / Qwen-VL：截图转 base64，只抽取题目文字，含 LaTeX）；
 *  - [streamImages] 多图视觉读题（翻动拼接后的连续片段，一次合并转写）；
 *  - [stream]      文本答题（DeepSeek：把读题结果交给推理模型解答）；
 *  - [streamImageAsk] 日常模式（千问 / Qwen-VL：截图 + 用户需求，直接给出操作指引或事实）。
 *
 * 端点由 [endpointFor] 依模型名解析，便于后续接入更多兼容 OpenAI 协议的服务。
 */
class ChatClient(
    private val apiKey: String,
    private val model: String,
    private val endpoint: String
) {

    @Volatile
    private var cancelled = false
    private var conn: HttpURLConnection? = null

    fun cancel() {
        cancelled = true
        conn?.disconnect()
    }

    /** 文本问答：system 设定角色，user 传入题目文字。 */
    fun stream(
        question: String,
        onDelta: (String) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
            .put(JSONObject().put("role", "user").put("content", question))
        post(messages, onDelta, onDone, onError)
    }

    /**
     * 视觉读题：把截图（JPEG）以 data URL 形式直传给多模态模型，只抽取题目文字（含 LaTeX）。
     * 不发送 system 角色，改为把指令并入 user 文本，兼容部分视觉模型的角色限制。
     */
    fun streamImage(
        imageBase64: String,
        onDelta: (String) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) = streamImages(listOf(imageBase64), onDelta, onDone, onError)

    /**
     * 视觉读题（多图）：一次传入多张同源截图，交给多模态模型合并转写。
     * 用于解题模式的「翻动拼接识别」——多张图是同一长页面自上而下滚动截取的连续片段。
     */
    fun streamImages(
        imageBase64List: List<String>,
        onDelta: (String) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        val content = JSONArray()
        for (imageBase64 in imageBase64List) {
            content.put(
                JSONObject()
                    .put("type", "image_url")
                    .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$imageBase64"))
            )
        }
        content.put(JSONObject().put("type", "text").put("text", READ_PROMPT))

        val messages = JSONArray().put(
            JSONObject().put("role", "user").put("content", content)
        )
        // 读题属纯转写任务，无需思考链；关闭思考可显著降本提速。
        post(messages, onDelta, onDone, onError, disableThinking = true)
    }

    /**
     * 日常模式：把截图与（可选的）用户需求一起交给视觉模型，直接得到操作指引或事实结论。
     * 不发送 system 角色，改为把指令并入 user 文本，兼容部分视觉模型的角色限制。
     */
    fun streamImageAsk(
        imageBase64: String,
        request: String,
        onDelta: (String) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        val req = request.trim()
        val instruction = if (req.isEmpty()) DAILY_PROMPT else DAILY_PROMPT + "\n\n用户的需求：" + req

        val imagePart = JSONObject()
            .put("type", "image_url")
            .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$imageBase64"))
        val textPart = JSONObject().put("type", "text").put("text", instruction)

        val messages = JSONArray().put(
            JSONObject()
                .put("role", "user")
                .put("content", JSONArray().put(imagePart).put(textPart))
        )
        // 指引需要即时呈现，关闭思考链以求直接、快速。
        post(messages, onDelta, onDone, onError, disableThinking = true)
    }

    private fun post(
        messages: JSONArray,
        onDelta: (String) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit,
        disableThinking: Boolean = false
    ) {
        try {
            val payload = JSONObject().apply {
                put("model", model)
                put("stream", true)
                put("messages", messages)
                if (disableThinking) put("enable_thinking", false)
            }

            val c = URL(endpoint).openConnection() as HttpURLConnection
            conn = c
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 20_000
            c.readTimeout = 180_000
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Authorization", "Bearer $apiKey")
            c.setRequestProperty("Accept", "text/event-stream")
            c.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

            val code = c.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                val raw = c.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                onError(parseError(code, raw))
                return
            }

            BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (cancelled) break
                    val text = line!!.trim()
                    if (!text.startsWith("data:")) continue
                    val data = text.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    val delta = runCatching {
                        JSONObject(data).optJSONArray("choices")
                            ?.optJSONObject(0)
                            ?.optJSONObject("delta")
                            ?.optString("content")
                    }.getOrNull()
                    if (!delta.isNullOrEmpty()) onDelta(delta)
                }
            }
            if (!cancelled) onDone()
        } catch (e: Exception) {
            if (!cancelled) onError(e.message ?: "网络异常")
        }
    }

    private fun parseError(code: Int, raw: String): String = runCatching {
        val obj = JSONObject(raw)
        val msg = obj.optJSONObject("error")?.optString("message").orEmpty()
        if (msg.isNotEmpty()) msg else "HTTP $code ${raw.take(200)}"
    }.getOrDefault("HTTP $code")

    companion object {
        /** DeepSeek 官方（文本模型）。 */
        const val DEEPSEEK_URL = "https://api.deepseek.com/chat/completions"

        /** 阿里云百炼 DashScope OpenAI 兼容端点（含 Qwen-VL 视觉模型）。 */
        const val QWEN_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"

        /** 依模型名解析服务端点：qwen* → 百炼，其余 → DeepSeek。 */
        fun endpointFor(model: String): String =
            if (Prefs.providerOf(model) == Prefs.PROVIDER_QWEN) QWEN_URL else DEEPSEEK_URL

        private const val LATEX_RULE =
            "排版要求：使用 Markdown 组织内容；所有数学公式一律使用标准 LaTeX 语法并用 \$ 包裹" +
                "（行内公式 \$...\$，独立成行的重要公式用 \$\$...\$\$）。分式、根式、上下标、积分、求和、" +
                "矩阵、方程组与对齐环境（如 \\frac、\\sqrt、\\int、\\sum、\\begin{cases}...\\end{cases}、" +
                "\\begin{aligned}...\\end{aligned}、\\begin{pmatrix}...\\end{pmatrix}）均可直接书写，无需回避。"

        private const val SYSTEM_PROMPT =
            "你是一个专业的答题助手。用户会发来由视觉模型从手机屏幕截图中识别出的题目文字" +
                "（可能存在少量识别错误、缺少选项或公式符号错乱）。请：\n" +
                "1) 先判断题型并简要分析；\n" +
                "2) 给出清晰的解题步骤；\n" +
                "3) 最后用【答案】明确给出最终结果。\n" +
                "若题目文字明显有误，请基于上下文合理推断并说明。回答使用中文，简洁准确。\n" +
                LATEX_RULE

        /** 读题提示词：只做识别与转写，不解答，供后续 DeepSeek 答题使用。 */
        private const val READ_PROMPT =
            "你是一个题目识别助手。请仔细阅读这张手机屏幕截图，把其中的题目完整、准确地转为文字，" +
                "供另一个模型解答。要求：\n" +
                "1) 只做识别与转写，不要解题、不要给答案、不要添加任何分析或评价；\n" +
                "2) 忠实还原题干、选项（如有）、已知条件、数值与单位；\n" +
                "3) 所有数学公式与符号（分式、根式、上下标、积分、求和、矩阵、方程组等）" +
                "一律使用标准 LaTeX 表示：行内用 \$...\$，独立成行的公式用 \$\$...\$\$；\n" +
                "4) 若截图中有多道题，按先后顺序全部转写，用空行分隔；\n" +
                "5) 若一次给了多张图片，它们是同一长页面自上而下依次滚动截取的连续片段" +
                "（已去除重叠），请视为一整段连续内容，按图片先后顺序自然衔接、合并转写，" +
                "并避免在衔接处重复同一句话或同一个公式；\n" +
                "6) 只输出题目文字，不要任何前言、后缀或解释性语句。"

        /**
         * 日常模式提示词：结合屏幕截图与用户需求，给出可直接照做的操作指引或事实结论。
         */
        private const val DAILY_PROMPT =
            "你是一个手机操作助手。用户会发来一张手机屏幕截图，可能附带一句文字需求（如未附带，则默认" +
                "请用户说明当前屏幕）。请严格依据截图中真实可见的内容作答：\n" +
                "1) 若用户提出了具体需求（如「怎么打开设置」「这个页面是做什么的」「这个提示什么意思」），" +
                "就围绕该需求给出准确、可直接照做的结论或步骤；\n" +
                "2) 若用户没有提出需求，则先说明当前是什么应用/页面、屏幕上有哪些主要可操作项，" +
                "再给出下一步建议；\n" +
                "3) 涉及多点操作时，按「第 1 步、第 2 步…」分点列出，明确按钮/菜单的名称与位置" +
                "（如「右下角蓝色的＋」「右上角三个点」）；\n" +
                "4) 只依据截图中确实可见的内容作答，看不清或不存在的内容不要臆测，可如实说明「截图中未见」；\n" +
                "5) 若用户问的是某个事实或含义，直接给出准确结论，无需罗列操作。\n" +
                "回答使用中文，简洁、具体、面向操作。" + LATEX_RULE
    }
}
