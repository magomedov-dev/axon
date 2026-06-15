package com.axon.agent.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.provider.Settings
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStatusBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnOpenSettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        setupLanguageToggle()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
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
        val enabled = AutomationAccessibilityService.instance != null
        binding.txtServiceState.setText(
            if (enabled) R.string.state_enabled else R.string.state_disabled
        )
        tintDot(binding.dotService, on = enabled)

        // WebSocket server status arrives in Stage 1; idle for now.
        binding.txtServerState.setText(R.string.state_idle)
        tintDot(binding.dotServer, on = false)
    }

    private fun tintDot(view: android.view.View, on: Boolean) {
        val color = ContextCompat.getColor(
            this,
            if (on) R.color.status_on else R.color.status_off
        )
        view.backgroundTintList = ColorStateList.valueOf(color)
    }
}
