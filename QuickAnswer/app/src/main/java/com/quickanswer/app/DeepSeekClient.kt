package com.quickanswer.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * DeepSeek 官方 API 客户端（OpenAI 兼容的 chat/completions，SSE 流式）。
 * 仅使用 HttpURLConnection + org.json，零第三方依赖。
 */
class DeepSeekClient(private val apiKey: String, private val model: String) {

    @Volatile
    private var cancelled = false
    private var conn: HttpURLConnection? = null

    fun cancel() {
        cancelled = true
        conn?.disconnect()
    }

    fun stream(
        question: String,
        onDelta: (String) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            val payload = JSONObject().apply {
                put("model", model)
                put("stream", true)
                put("messages", JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                    put(JSONObject().put("role", "user").put("content", question))
                })
            }

            val c = URL(API_URL).openConnection() as HttpURLConnection
            conn = c
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 15_000
            c.readTimeout = 120_000
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
        val msg = JSONObject(raw).optJSONObject("error")?.optString("message").orEmpty()
        if (msg.isNotEmpty()) msg else "HTTP $code ${raw.take(200)}"
    }.getOrDefault("HTTP $code")

    companion object {
        private const val API_URL = "https://api.deepseek.com/chat/completions"

        private const val SYSTEM_PROMPT =
            "你是一个专业的答题助手。用户会发来从手机屏幕上通过 OCR 识别出的题目文字" +
                "（可能存在少量识别错误、缺少选项或公式符号错乱）。请：\n" +
                "1) 先判断题型并简要分析；\n" +
                "2) 给出清晰的解题步骤；\n" +
                "3) 最后用【答案】明确给出最终结果。\n" +
                "若题目文字明显有误，请基于上下文合理推断并说明。回答使用中文，简洁准确。\n" +
                "数学公式请使用 LaTeX 行内语法并用 \$ 包裹（例如 \$x^2\$、\$\\frac{a}{b}\$、\$\\sqrt{x}\$），" +
                "避免使用 \\begin{} 环境或矩阵等复杂排版。"
    }
}
