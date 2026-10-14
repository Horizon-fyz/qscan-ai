package com.quickanswer.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 独立 API 密钥库：保存用户从各厂商收集到的 Key（厂商、请求地址、模型、是否自定义）。
 *
 * 仅存本机 SharedPreferences（JSON 数组），与 [Prefs] 的运行期配置解耦；
 * 命中内置通道时由调用方把 Key 回写到 [Prefs]，供悬浮窗直接使用。
 * 每个厂商（含「自定义」）至多一条，重复保存即覆盖。
 */
object ApiKeys {

    private const val FILE = "quickanswer_api_keys"
    private const val KEY_LIST = "entries"

    /** 自定义通道在密钥库中的固定 id。 */
    const val CUSTOM_ID = "custom"

    data class Entry(
        val vendorId: String,
        val name: String,
        val baseUrl: String,
        val apiKey: String,
        val custom: Boolean,
        val model: String,
        val savedAt: Long
    )

    /** 全部条目，最近保存的在前。 */
    fun all(context: Context): List<Entry> {
        val raw = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_LIST, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Entry(
                    vendorId = o.optString("vendorId"),
                    name = o.optString("name"),
                    baseUrl = o.optString("baseUrl"),
                    apiKey = o.optString("apiKey"),
                    custom = o.optBoolean("custom"),
                    model = o.optString("model"),
                    savedAt = o.optLong("savedAt")
                )
            }.filter { it.apiKey.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    fun find(context: Context, vendorId: String): Entry? =
        all(context).firstOrNull { it.vendorId == vendorId }

    /** 新增或按 vendorId 覆盖。 */
    fun upsert(context: Context, entry: Entry) {
        val list = all(context).filterNot { it.vendorId == entry.vendorId }.toMutableList()
        list.add(0, entry)
        save(context, list)
    }

    fun remove(context: Context, vendorId: String) {
        save(context, all(context).filterNot { it.vendorId == vendorId })
    }

    private fun save(context: Context, list: List<Entry>) {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(
                JSONObject()
                    .put("vendorId", e.vendorId)
                    .put("name", e.name)
                    .put("baseUrl", e.baseUrl)
                    .put("apiKey", e.apiKey)
                    .put("custom", e.custom)
                    .put("model", e.model)
                    .put("savedAt", e.savedAt)
            )
        }
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY_LIST, arr.toString()).apply()
    }
}
