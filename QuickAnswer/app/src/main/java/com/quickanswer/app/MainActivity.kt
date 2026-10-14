package com.quickanswer.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.quickanswer.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    /** API Key 输入框当前对应的提供商；切换模型时先保存旧 Key 再载入新 Key，避免互相覆盖 */
    private var currentProvider = Prefs.PROVIDER_DEEPSEEK

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            saveKey()
            FloatingService.start(this, result.resultCode, data)
            binding.tvStatus.text = getString(R.string.status_started)
        } else {
            binding.tvStatus.text = getString(R.string.status_need_overlay)
        }
    }

    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        binding.tvStatus.text = if (granted) {
            getString(R.string.status_idle)
        } else {
            getString(R.string.step_notify)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        currentProvider = Prefs.providerOf(Prefs.model(this))
        binding.etKey.setText(Prefs.apiKey(this, currentProvider))
        selectModel(Prefs.model(this))

        binding.segChat.setOnClickListener { selectModel(Prefs.MODEL_CHAT) }
        binding.segReasoner.setOnClickListener { selectModel(Prefs.MODEL_REASONER) }
        binding.segQwen.setOnClickListener { selectModel(Prefs.MODEL_QWEN_VL) }

        binding.btnOverlay.setOnClickListener { requestOverlayPermission() }
        binding.btnNotify.setOnClickListener { requestNotificationPermission() }
        binding.btnStart.setOnClickListener { startFloating() }
        binding.btnStop.setOnClickListener {
            FloatingService.stop(this)
            binding.tvStatus.text = getString(R.string.status_stopped)
        }
    }

    override fun onResume() {
        super.onResume()
        binding.btnOverlay.text = if (Settings.canDrawOverlays(this)) {
            getString(R.string.step_overlay) + "（已授权）"
        } else {
            getString(R.string.step_overlay)
        }
    }

    private fun selectModel(model: String) {
        val provider = Prefs.providerOf(model)
        if (provider != currentProvider) {
            // 切换提供商：先把输入框里的 Key 存回旧提供商，再载入新提供商的 Key
            saveKey()
            currentProvider = provider
            binding.etKey.setText(Prefs.apiKey(this, provider))
        }
        Prefs.setModel(this, model)

        styleSeg(binding.segChat, model == Prefs.MODEL_CHAT)
        styleSeg(binding.segReasoner, model == Prefs.MODEL_REASONER)
        styleSeg(binding.segQwen, model == Prefs.MODEL_QWEN_VL)

        val isQwen = provider == Prefs.PROVIDER_QWEN
        binding.etKey.setHint(if (isQwen) R.string.api_key_hint_qwen else R.string.api_key_hint)
        binding.tvKeyNote.setText(if (isQwen) R.string.api_key_note_qwen else R.string.api_key_note)
    }

    private fun styleSeg(view: TextView, selected: Boolean) {
        view.setBackgroundResource(
            if (selected) R.drawable.bg_segment_selected else R.drawable.bg_segment_default
        )
        view.setTextColor(ContextCompat.getColor(this, if (selected) R.color.on_dark else R.color.steel))
    }

    private fun saveKey() {
        Prefs.setApiKey(this, currentProvider, binding.etKey.text.toString())
    }

    private fun requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            binding.tvStatus.text = getString(R.string.status_idle)
            return
        }
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED
            ) {
                binding.tvStatus.text = getString(R.string.status_idle)
            } else {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            binding.tvStatus.text = getString(R.string.status_idle)
        }
    }

    private fun startFloating() {
        if (binding.etKey.text.toString().trim().isEmpty()) {
            binding.tvStatus.text = getString(R.string.status_need_key)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            binding.tvStatus.text = getString(R.string.status_need_overlay)
            requestOverlayPermission()
            return
        }
        saveKey()
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }
}
