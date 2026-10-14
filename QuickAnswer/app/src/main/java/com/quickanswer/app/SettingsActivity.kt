package com.quickanswer.app

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.quickanswer.app.databinding.ActivitySettingsBinding

/** 密钥设置页：两把 API Key + 答题模型 + 默认模式，改动即时保存到本机。 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        binding.etQwenKey.setText(Prefs.apiKey(this, Prefs.PROVIDER_QWEN))
        binding.etDsKey.setText(Prefs.apiKey(this, Prefs.PROVIDER_DEEPSEEK))
        binding.etQwenKey.doAfterTextChanged {
            Prefs.setApiKey(this, Prefs.PROVIDER_QWEN, it?.toString().orEmpty())
        }
        binding.etDsKey.doAfterTextChanged {
            Prefs.setApiKey(this, Prefs.PROVIDER_DEEPSEEK, it?.toString().orEmpty())
        }

        // 旧版本可能残留「千问视觉」等非答题模型，回落到默认文本模型
        val saved = Prefs.model(this)
        val answerModel = if (saved == Prefs.MODEL_REASONER) Prefs.MODEL_REASONER else Prefs.MODEL_CHAT
        selectModel(answerModel)

        binding.segChat.setOnClickListener { selectModel(Prefs.MODEL_CHAT) }
        binding.segReasoner.setOnClickListener { selectModel(Prefs.MODEL_REASONER) }

        selectMode(Prefs.mode(this))
        binding.segModeSolve.setOnClickListener { selectMode(Prefs.MODE_SOLVE) }
        binding.segModeDaily.setOnClickListener { selectMode(Prefs.MODE_DAILY) }
        binding.segModeRealtime.setOnClickListener { selectMode(Prefs.MODE_REALTIME) }

        selectTheme(Prefs.theme(this))
        binding.segThemeSystem.setOnClickListener { selectTheme(Prefs.THEME_SYSTEM) }
        binding.segThemeLight.setOnClickListener { selectTheme(Prefs.THEME_LIGHT) }
        binding.segThemeDark.setOnClickListener { selectTheme(Prefs.THEME_DARK) }
    }

    private fun selectModel(model: String) {
        Prefs.setModel(this, model)
        styleSeg(binding.segChat, model == Prefs.MODEL_CHAT)
        styleSeg(binding.segReasoner, model == Prefs.MODEL_REASONER)
    }

    private fun selectMode(mode: String) {
        Prefs.setMode(this, mode)
        styleSeg(binding.segModeSolve, mode == Prefs.MODE_SOLVE)
        styleSeg(binding.segModeDaily, mode == Prefs.MODE_DAILY)
        styleSeg(binding.segModeRealtime, mode == Prefs.MODE_REALTIME)
    }

    private fun selectTheme(theme: String) {
        Prefs.setTheme(this, theme)
        AppCompatDelegate.setDefaultNightMode(App.modeOf(theme))
        styleSeg(binding.segThemeSystem, theme == Prefs.THEME_SYSTEM)
        styleSeg(binding.segThemeLight, theme == Prefs.THEME_LIGHT)
        styleSeg(binding.segThemeDark, theme == Prefs.THEME_DARK)
    }

    private fun styleSeg(view: TextView, selected: Boolean) {
        view.setBackgroundResource(
            if (selected) R.drawable.bg_segment_selected else R.drawable.bg_segment_default
        )
        view.setTextColor(ContextCompat.getColor(this, if (selected) R.color.on_primary else R.color.steel))
    }
}
