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

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            saveKeys()
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

    // 录音权限仅用于日常模式的语音输入；拒绝不阻塞启动，只是语音不可用。
    private val micLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { launchProjection() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.etQwenKey.setText(Prefs.apiKey(this, Prefs.PROVIDER_QWEN))
        binding.etDsKey.setText(Prefs.apiKey(this, Prefs.PROVIDER_DEEPSEEK))

        // 旧版本可能残留「千问视觉」等非答题模型，回落到默认文本模型
        val saved = Prefs.model(this)
        val answerModel = if (saved == Prefs.MODEL_REASONER) Prefs.MODEL_REASONER else Prefs.MODEL_CHAT
        selectModel(answerModel)

        binding.segChat.setOnClickListener { selectModel(Prefs.MODEL_CHAT) }
        binding.segReasoner.setOnClickListener { selectModel(Prefs.MODEL_REASONER) }

        selectMode(Prefs.mode(this))
        binding.segModeSolve.setOnClickListener { selectMode(Prefs.MODE_SOLVE) }
        binding.segModeDaily.setOnClickListener { selectMode(Prefs.MODE_DAILY) }

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
        Prefs.setModel(this, model)
        styleSeg(binding.segChat, model == Prefs.MODEL_CHAT)
        styleSeg(binding.segReasoner, model == Prefs.MODEL_REASONER)
    }

    private fun selectMode(mode: String) {
        Prefs.setMode(this, mode)
        styleSeg(binding.segModeSolve, mode == Prefs.MODE_SOLVE)
        styleSeg(binding.segModeDaily, mode == Prefs.MODE_DAILY)
    }

    private fun styleSeg(view: TextView, selected: Boolean) {
        view.setBackgroundResource(
            if (selected) R.drawable.bg_segment_selected else R.drawable.bg_segment_default
        )
        view.setTextColor(ContextCompat.getColor(this, if (selected) R.color.on_dark else R.color.steel))
    }

    private fun saveKeys() {
        Prefs.setApiKey(this, Prefs.PROVIDER_QWEN, binding.etQwenKey.text.toString())
        Prefs.setApiKey(this, Prefs.PROVIDER_DEEPSEEK, binding.etDsKey.text.toString())
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
        if (binding.etQwenKey.text.toString().trim().isEmpty() ||
            binding.etDsKey.text.toString().trim().isEmpty()
        ) {
            binding.tvStatus.text = getString(R.string.status_need_key)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            binding.tvStatus.text = getString(R.string.status_need_overlay)
            requestOverlayPermission()
            return
        }
        saveKeys()
        // 首次进入时申请录音权限（用于日常模式语音输入），授权后再拉起投屏。
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            launchProjection()
        } else {
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun launchProjection() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }
}
