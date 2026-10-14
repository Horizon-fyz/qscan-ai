package com.quickanswer.app

import android.content.Context

/**
 * 本地配置：API Key 与模型选择，仅保存在本机 SharedPreferences。
 * Key 按「服务提供商」分别存储：DeepSeek（文本，本地 OCR 后提问）与
 * 千问（视觉，截图直传多模态模型）。模型名以 qwen 开头即视为千问提供商。
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

    /** 该提供商是否为「视觉直传」模式（跳过本地 OCR，截图直接交给多模态模型）。 */
    fun isVision(provider: String): Boolean = provider == PROVIDER_QWEN

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

    /** 当前所选模型对应的 Key（供悬浮窗服务使用）。 */
    fun apiKeyForModel(context: Context): String = apiKey(context, providerOf(model(context)))

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

    /** 百炼（DashScope）视觉模型：可用 qwen3.7-plus / qwen3.8-max / qwen3.8-flash 等，按需替换。 */
    const val MODEL_QWEN_VL = "qwen3.7-plus"
}
