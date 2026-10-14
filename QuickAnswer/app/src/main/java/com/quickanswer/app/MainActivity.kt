package com.quickanswer.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.quickanswer.app.databinding.ActivityMainBinding

/** 首页：只做两件事——打开「密钥设置 / 使用说明」入口，以及启动与停止悬浮窗。 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
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

        binding.cardKeys.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.cardHelp.setOnClickListener {
            startActivity(Intent(this, HelpActivity::class.java))
        }
        binding.cardFiles.setOnClickListener {
            startActivity(Intent(this, FileManagerActivity::class.java))
        }

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
        // 密钥已在「密钥设置」页保存到本机，这里直接读取校验收否填写。
        if (Prefs.apiKey(this, Prefs.PROVIDER_QWEN).isBlank() ||
            Prefs.apiKey(this, Prefs.PROVIDER_DEEPSEEK).isBlank()
        ) {
            binding.tvStatus.text = getString(R.string.status_need_key)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            binding.tvStatus.text = getString(R.string.status_need_overlay)
            requestOverlayPermission()
            return
        }
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
        // Android 14+ 默认弹窗会让用户选「整个屏幕 / 单个应用」，强制只提供整屏捕获。
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        projectionLauncher.launch(intent)
    }
}
