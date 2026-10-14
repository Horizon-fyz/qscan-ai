package com.quickanswer.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

/**
 * 应用入口：启动即套用用户选择的外观（默认跟随系统深浅色）。
 *
 * 通过 [AppCompatDelegate.setDefaultNightMode] 在进程级生效，
 * 使所有 Activity 与悬浮窗服务统一按日/夜模式解析资源。
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(modeOf(Prefs.theme(this)))
        // 启动即持续识别剪贴板：识别到的 API Key / 厂商链接交由前台界面处理。
        ClipboardWatcher.start(this)
    }

    companion object {
        /** 外观偏好 → AppCompatDelegate 夜间模式常量。 */
        fun modeOf(theme: String): Int = when (theme) {
            Prefs.THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            Prefs.THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
    }
}
