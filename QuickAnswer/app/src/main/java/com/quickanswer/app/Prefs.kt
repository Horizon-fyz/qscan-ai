package com.quickanswer.app

import android.content.Context
import android.content.SharedPreferences

/**
 * 本地配置：API Key、答题模型与工作模式，仅保存在本机 SharedPreferences。
 *
 * 两段式流水线各用一把 Key：
 *  - 千问（[PROVIDER_QWEN]）：视觉读题 / 日常模式直接生成指引；
 *  - DeepSeek（[PROVIDER_DEEPSEEK]）：解题模式下的文本答题。
 * 两把 Key 分别持久化，互不覆盖。
 */
object Prefs {
    private const val FILE = "quickanswer_prefs"
    private const val KEY_API_DEEPSEEK = "api_key_deepseek"
    private const val KEY_API_QWEN = "api_key_qwen"
    private const val KEY_MODEL = "model"

    /** 工作模式键：悬浮窗服务据此监听模式切换。 */
    const val KEY_MODE = "mode"

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

    // ---------------------------------------------------------------- 工作模式

    /** 解题模式：截图 → 千问读题 → DeepSeek 解题。 */
    const val MODE_SOLVE = "solve"

    /** 日常模式：截图 +（可选的）用户需求 → 千问直接给出操作指引或事实。 */
    const val MODE_DAILY = "daily"

    /** 实时模式：持续截屏观察屏幕，检测到变化即交千问即时回答；支持语音/文字需求。 */
    const val MODE_REALTIME = "realtime"

    fun mode(context: Context): String =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_MODE, MODE_SOLVE).orEmpty()

    fun setMode(context: Context, value: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY_MODE, value).apply()
    }

    /**
     * 注册/注销模式变化监听：悬浮窗服务与主界面同进程，
     * 主界面切换模式后，服务据此实时刷新悬浮球文案与面板形态。
     */
    fun registerModeListener(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterModeListener(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(listener)
    }

    /** 读题固定使用的百炼（DashScope）视觉模型；如 qwen3.7-plus / qwen3-vl-plus 等。 */
    const val MODEL_QWEN_VISION = "qwen3.7-plus"
}
