package com.quickanswer.app

import android.content.Context

/**
 * 本地配置：API Key 与答题模型选择，仅保存在本机 SharedPreferences。
 *
 * 两段式流水线各用一把 Key：
 *  - 千问（[PROVIDER_QWEN]）：视觉读题，截图转文字；
 *  - DeepSeek（[PROVIDER_DEEPSEEK]）：文本答题。
 * 两把 Key 分别持久化，互不覆盖。
 */
object Prefs {
    private const val FILE = "quickanswer_prefs"
    private const val KEY_API_DEEPSEEK = "api_key_deepseek"
    private const val KEY_API_QWEN = "api_key_qwen"
    private const val KEY_MODEL = "model"

    // ---------------------------------------------------------------- 提供商

    const val PROVIDER_DEEPSEEK = "deepseek"
    const val PROVIDER_QWEN = "qwen"

    /** 由模型名推断提供商：qwen* → 千问，其余 → DeepSeek。 */
    fun providerOf(model: String): String =
        if (model.startsWith("qwen")) PROVIDER_QWEN else PROVIDER_DEEPSEEK

    // ---------------------------------------------------------------- API Key

    private fun apiKeyField(provider: String): String =
        if (provider == PROVIDER_QWEN) KEY_API_QWEN else KEY_API_DEEPSEEK

    fun apiKey(context: Context, provider: String): String =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(apiKeyField(provider), "").orEmpty()

    fun setApiKey(context: Context, provider: String, value: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(apiKeyField(provider), value.trim()).apply()
    }

    // ---------------------------------------------------------------- 模型

    fun model(context: Context): String =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_MODEL, MODEL_CHAT).orEmpty()

    fun setModel(context: Context, value: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY_MODEL, value).apply()
    }

    const val MODEL_CHAT = "deepseek-chat"
    const val MODEL_REASONER = "deepseek-reasoner"

    /** 读题固定使用的百炼（DashScope）视觉模型；如 qwen3.7-plus / qwen3-vl-plus 等。 */
    const val MODEL_QWEN_VISION = "qwen3.7-plus"
}
