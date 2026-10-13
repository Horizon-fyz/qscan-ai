package com.quickanswer.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
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
            Prefs.setApiKey(this, binding.etKey.text.toString())
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

        binding.etKey.setText(Prefs.apiKey(this))
        selectModel(Prefs.model(this))

        binding.segChat.setOnClickListener { selectModel(Prefs.MODEL_CHAT) }
        binding.segReasoner.setOnClickListener { selectModel(Prefs.MODEL_REASONER) }

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
        val isChat = model != Prefs.MODEL_REASONER
        Prefs.setModel(this, if (isChat) Prefs.MODEL_CHAT else Prefs.MODEL_REASONER)

        binding.segChat.setBackgroundResource(
            if (isChat) R.drawable.bg_segment_selected else R.drawable.bg_segment_default
        )
        binding.segReasoner.setBackgroundResource(
            if (isChat) R.drawable.bg_segment_default else R.drawable.bg_segment_selected
        )
        binding.segChat.setTextColor(ContextCompat.getColor(this, if (isChat) R.color.on_dark else R.color.steel))
        binding.segReasoner.setTextColor(ContextCompat.getColor(this, if (isChat) R.color.steel else R.color.on_dark))
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
        Prefs.setApiKey(this, binding.etKey.text.toString())
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }
}
