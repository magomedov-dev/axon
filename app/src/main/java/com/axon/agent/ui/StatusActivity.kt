package com.axon.agent.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import com.axon.agent.AutomationAccessibilityService
import com.axon.agent.R
import com.axon.agent.databinding.ActivityStatusBinding

/**
 * Status / control screen.
 *
 * Shows whether the accessibility service is connected (and, from later stages,
 * the WebSocket server state), offers a shortcut into Accessibility settings,
 * and lets the user switch the app language between Russian and English at
 * runtime via the per-app locale API (auto-persisted by AppCompat).
 */
class StatusActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStatusBinding

    private val handler = Handler(Looper.getMainLooper())
    private var updatingSwitch = false
    private val tick = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStatusBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnOpenSettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // Tapping the logo shows a toast — a deterministic source for the
        // `toast` server-push event (used by the Stage 7 E2E test).
        binding.imgLogo.setOnClickListener {
            Toast.makeText(this, R.string.probe_toast, Toast.LENGTH_SHORT).show()
        }

        // Start/stop the WebSocket server. Ignore programmatic state syncs.
        binding.swServer.setOnCheckedChangeListener { _, isChecked ->
            if (updatingSwitch) return@setOnCheckedChangeListener
            AutomationAccessibilityService.instance?.setServerEnabled(isChecked)
            refreshStatus()
        }

        setupLanguageToggle()
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick) // refresh now and keep the connection count live
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    // ---- language ---------------------------------------------------------
    private fun setupLanguageToggle() {
        // Reflect the current language first, THEN attach the listener, so the
        // programmatic check below doesn't fire a spurious locale change.
        val checked = if (currentLangTag() == "ru") R.id.btnLangRu else R.id.btnLangEn
        binding.toggleLang.check(checked)

        binding.toggleLang.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val desired = if (checkedId == R.id.btnLangRu) "ru" else "en"
            if (desired != currentLangTag()) {
                AppCompatDelegate.setApplicationLocales(
                    LocaleListCompat.forLanguageTags(desired)
                )
            }
        }
    }

    private fun currentLangTag(): String {
        val locales = AppCompatDelegate.getApplicationLocales()
        val lang = if (!locales.isEmpty) {
            locales[0]?.language
        } else {
            resources.configuration.locales[0].language
        }
        return if (lang == "ru") "ru" else "en"
    }

    // ---- status -----------------------------------------------------------
    private fun refreshStatus() {
        val service = AutomationAccessibilityService.instance
        val enabled = service != null
        binding.txtServiceState.setText(
            if (enabled) R.string.state_enabled else R.string.state_disabled
        )
        tintDot(binding.dotService, on = enabled)

        val serverRunning = service?.isServerRunning() == true
        if (serverRunning) {
            binding.txtServerState.text =
                getString(R.string.fmt_server_listening, service.connectionCount())
        } else {
            binding.txtServerState.setText(
                if (enabled) R.string.state_stopped else R.string.state_idle
            )
        }
        tintDot(binding.dotServer, on = serverRunning)

        // The server can only be toggled while the accessibility service is bound.
        binding.swServer.isEnabled = enabled
        if (binding.swServer.isChecked != serverRunning) {
            updatingSwitch = true
            binding.swServer.isChecked = serverRunning
            updatingSwitch = false
        }
    }

    companion object {
        private const val REFRESH_INTERVAL_MS = 1000L
    }

    private fun tintDot(view: android.view.View, on: Boolean) {
        val color = ContextCompat.getColor(
            this,
            if (on) R.color.status_on else R.color.status_off
        )
        view.backgroundTintList = ColorStateList.valueOf(color)
    }
}
