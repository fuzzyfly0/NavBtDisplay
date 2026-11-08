package io.github.fuzzyfly0.navbtdisplay

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView

/** Minimal settings screen. */
class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var accessStatus: TextView
    private lateinit var lastHint: TextView
    private lateinit var logPreview: TextView

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.KEY_LAST_HINT) runOnUiThread(::refreshDynamic)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        accessStatus = findViewById(R.id.access_status)
        lastHint = findViewById(R.id.last_hint)
        logPreview = findViewById(R.id.log_preview)

        findViewById<Button>(R.id.grant_access).setOnClickListener { openAccessSettings() }

        findViewById<Switch>(R.id.enabled).apply {
            isChecked = prefs.enabled
            setOnCheckedChangeListener { _, on ->
                prefs.enabled = on
                if (!on) NavPublisher.stop(this@MainActivity)
            }
        }

        val modeIds = mapOf(
            DisplayMode.FLASH to R.id.mode_flash,
            DisplayMode.TAKEOVER to R.id.mode_takeover,
            DisplayMode.PASSIVE to R.id.mode_passive,
        )
        findViewById<RadioGroup>(R.id.mode).apply {
            check(modeIds.getValue(prefs.mode))
            setOnCheckedChangeListener { _, id ->
                val mode = modeIds.entries.first { it.value == id }.key
                if (mode != prefs.mode) {
                    prefs.mode = mode
                    // Start clean; the next guidance update applies the new mode.
                    NavPublisher.stop(this@MainActivity)
                }
            }
        }

        val appList = findViewById<LinearLayout>(R.id.apps)
        for (app in NavApps.ALL) {
            val installed = app.packages.any(::isInstalled)
            appList.addView(
                CheckBox(this).apply {
                    text = getString(
                        R.string.app_entry,
                        app.label,
                        getString(if (installed) R.string.app_installed else R.string.app_not_installed),
                    )
                    isChecked = app.key in prefs.enabledApps
                    setOnCheckedChangeListener { _, on ->
                        prefs.enabledApps = if (on) prefs.enabledApps + app.key else prefs.enabledApps - app.key
                    }
                },
            )
        }

        findViewById<Button>(R.id.test_hint).setOnClickListener { NavPublisher.showTest(this) }

        findViewById<Switch>(R.id.debug_log).apply {
            isChecked = prefs.debugLog
            setOnCheckedChangeListener { _, on -> prefs.debugLog = on }
        }
        findViewById<Button>(R.id.share_log).setOnClickListener { shareLog() }
        findViewById<Button>(R.id.clear_log).setOnClickListener {
            DebugLog.clear(this)
            refreshDynamic()
        }
    }

    override fun onResume() {
        super.onResume()
        prefs.sp.registerOnSharedPreferenceChangeListener(prefsListener)
        refreshDynamic()
    }

    override fun onPause() {
        prefs.sp.unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onPause()
    }

    private fun refreshDynamic() {
        accessStatus.setText(if (hasNotificationAccess()) R.string.access_granted else R.string.access_missing)
        lastHint.text = prefs.lastHint ?: getString(R.string.no_hint_yet)
        // Only the newest part of the log, to keep the screen responsive.
        val log = DebugLog.read(this)
        logPreview.text = if (log.isBlank()) getString(R.string.log_empty) else log.takeLast(6_000)
    }

    private fun hasNotificationAccess(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        val me = ComponentName(this, NavListenerService::class.java)
        return flat.split(':').mapNotNull(ComponentName::unflattenFromString).any { it == me }
    }

    private fun openAccessSettings() {
        val component = ComponentName(this, NavListenerService::class.java)
        val detail = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
        } else {
            null
        }
        val fallback = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        runCatching { startActivity(detail ?: fallback) }.onFailure { startActivity(fallback) }
    }

    private fun shareLog() {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "${getString(R.string.app_name)} debug log")
            .putExtra(Intent.EXTRA_TEXT, DebugLog.read(this).takeLast(100_000))
        startActivity(Intent.createChooser(send, getString(R.string.share_log)))
    }

    private fun isInstalled(pkg: String): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(pkg, 0)
        }
    }.isSuccess
}
