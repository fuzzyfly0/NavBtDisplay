package io.github.fuzzyfly0.navbtdisplay

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
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
    private lateinit var captureButton: Button
    private val handler = Handler(Looper.getMainLooper())

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

        findViewById<Switch>(R.id.rotate_music).apply {
            isChecked = prefs.rotateMusic
            setOnCheckedChangeListener { _, on ->
                prefs.rotateMusic = on
                NavPublisher.refresh(this@MainActivity)
            }
        }

        findViewById<Switch>(R.id.reclaim_on_maneuver).apply {
            isChecked = prefs.reclaimOnManeuver
            setOnCheckedChangeListener { _, on -> prefs.reclaimOnManeuver = on }
        }
        findViewById<Switch>(R.id.new_track_per_maneuver).apply {
            isChecked = prefs.newTrackPerManeuver
            setOnCheckedChangeListener { _, on -> prefs.newTrackPerManeuver = on }
        }

        val artIds = mapOf(
            ArtMode.OFF to R.id.art_off,
            ArtMode.NOTIFICATION to R.id.art_notification,
            ArtMode.SCREEN to R.id.art_screen,
        )
        findViewById<RadioGroup>(R.id.art_mode).apply {
            check(artIds.getValue(prefs.artMode))
            setOnCheckedChangeListener { _, id ->
                val mode = artIds.entries.first { it.value == id }.key
                prefs.artMode = mode
                if (mode != ArtMode.SCREEN) ScreenCaptureService.stop(this@MainActivity)
                NavPublisher.refresh(this@MainActivity)
                refreshDynamic()
            }
        }
        val intervalIds = mapOf(3 to R.id.art_interval_3, 5 to R.id.art_interval_5, 10 to R.id.art_interval_10)
        findViewById<RadioGroup>(R.id.art_interval).apply {
            check(intervalIds[prefs.artIntervalSec] ?: R.id.art_interval_5)
            setOnCheckedChangeListener { _, id ->
                prefs.artIntervalSec = intervalIds.entries.first { it.value == id }.key
            }
        }
        captureButton = findViewById<Button>(R.id.screen_capture).apply {
            setOnClickListener {
                if (ScreenCaptureService.running) {
                    ScreenCaptureService.stop(this@MainActivity)
                    handler.postDelayed(::refreshDynamic, 500)
                } else {
                    startCapture()
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

    private fun startCapture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            // Only for the "screen sharing active" notification; capture works without it.
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    @Deprecated("Activity result API of the platform Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CAPTURE && resultCode == RESULT_OK && data != null) {
            ScreenCaptureService.start(this, resultCode, data)
            handler.postDelayed(::refreshDynamic, 500)
        }
    }

    private fun refreshDynamic() {
        captureButton.visibility = if (prefs.artMode == ArtMode.SCREEN) View.VISIBLE else View.GONE
        captureButton.setText(if (ScreenCaptureService.running) R.string.capture_stop else R.string.capture_start)
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

    private companion object {
        const val REQ_CAPTURE = 1
        const val REQ_NOTIFICATIONS = 2
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
