package com.quickanswer.app

import android.content.Context

/** 本地配置：API Key 与模型选择，仅保存在本机 SharedPreferences。 */
object Prefs {
    private const val FILE = "quickanswer_prefs"
    private const val KEY_API = "api_key"
    private const val KEY_MODEL = "model"

    fun apiKey(context: Context): String =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_API, "").orEmpty()

    fun setApiKey(context: Context, value: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY_API, value.trim()).apply()
    }

    fun model(context: Context): String =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_MODEL, MODEL_CHAT).orEmpty()

    fun setModel(context: Context, value: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY_MODEL, value).apply()
    }

    const val MODEL_CHAT = "deepseek-chat"
    const val MODEL_REASONER = "deepseek-reasoner"
}
