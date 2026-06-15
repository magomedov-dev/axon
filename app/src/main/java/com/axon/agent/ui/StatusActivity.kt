package com.axon.agent.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import com.axon.agent.AutomationAccessibilityService
import com.axon.agent.R
import com.axon.agent.databinding.ActivityStatusBinding

/**
 * Status / control screen.
 *
 * Stage 0: shows whether the accessibility service is connected and offers a
 * shortcut into the system Accessibility settings. The WebSocket server status
 * and the start/stop toggle are filled in later (Stages 1, 8, 9).
 */
class StatusActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStatusBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStatusBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnOpenSettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val enabled = AutomationAccessibilityService.instance != null
        binding.txtServiceStatus.setText(
            if (enabled) R.string.status_service_on else R.string.status_service_off
        )
    }
}
