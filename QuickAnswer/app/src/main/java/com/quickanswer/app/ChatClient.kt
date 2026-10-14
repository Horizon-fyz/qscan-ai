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
 * 支持两类调用：
 *  - [stream]      文本问答（DeepSeek：本地 OCR 出题目文字后提问）；
 *  - [streamImage] 多模态视觉问答（千问 / Qwen-VL：截图转 base64 直接读题作答）。
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
     * 视觉问答：把截图（JPEG）以 data URL 形式直传给多模态模型，跳过本地 OCR。
     * 不发送 system 角色，改为把指令并入 user 文本，兼容部分视觉模型的角色限制。
     */
    fun streamImage(
        imageBase64: String,
        onDelta: (String) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        val imagePart = JSONObject()
            .put("type", "image_url")
            .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$imageBase64"))
        val textPart = JSONObject()
            .put("type", "text")
            .put("text", VISION_PROMPT)

        val messages = JSONArray().put(
            JSONObject()
                .put("role", "user")
                .put("content", JSONArray().put(imagePart).put(textPart))
        )
        post(messages, onDelta, onDone, onError)
    }

    private fun post(
        messages: JSONArray,
        onDelta: (String) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            val payload = JSONObject().apply {
                put("model", model)
                put("stream", true)
                put("messages", messages)
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
            "你是一个专业的答题助手。用户会发来从手机屏幕上通过 OCR 识别出的题目文字" +
                "（可能存在少量识别错误、缺少选项或公式符号错乱）。请：\n" +
                "1) 先判断题型并简要分析；\n" +
                "2) 给出清晰的解题步骤；\n" +
                "3) 最后用【答案】明确给出最终结果。\n" +
                "若题目文字明显有误，请基于上下文合理推断并说明。回答使用中文，简洁准确。\n" +
                LATEX_RULE

        private const val VISION_PROMPT =
            "请阅读这张手机屏幕截图中的题目并作答。要求：\n" +
                "1) 先用一两句话概括你识别到的题目内容，便于用户核对；\n" +
                "2) 给出清晰的解题步骤；\n" +
                "3) 最后用【答案】明确给出最终结果。\n" +
                "若截图中有多道题目或内容不完整，请选择最主要的一道作答并说明。回答使用中文，简洁准确。\n" +
                LATEX_RULE
    }
}
