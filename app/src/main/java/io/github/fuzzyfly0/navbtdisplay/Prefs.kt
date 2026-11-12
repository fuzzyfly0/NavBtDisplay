package io.github.fuzzyfly0.navbtdisplay

import android.content.Context
import android.content.SharedPreferences

/** How aggressively the app takes over the car's "now playing" display. */
enum class DisplayMode {
    /** Keep the car display on navigation for as long as guidance runs. */
    TAKEOVER,

    /** Show each new maneuver for a few seconds, then hand back to the music app. */
    FLASH,

    /** Only publish a media session, never play (silent) audio. Usually invisible while music plays. */
    PASSIVE,
}

class Prefs(context: Context) {
    val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, true)
        set(v) = sp.edit().putBoolean(KEY_ENABLED, v).apply()

    var enabledApps: Set<String>
        get() = sp.getStringSet(KEY_APPS, null) ?: NavApps.ALL.map { it.key }.toSet()
        set(v) = sp.edit().putStringSet(KEY_APPS, v).apply()

    var mode: DisplayMode
        get() = runCatching { DisplayMode.valueOf(sp.getString(KEY_MODE, null)!!) }
            .getOrDefault(DisplayMode.TAKEOVER)
        set(v) = sp.edit().putString(KEY_MODE, v.name).apply()

    /** In TAKEOVER mode: alternate the first line between maneuver and the music title. */
    var rotateMusic: Boolean
        get() = sp.getBoolean(KEY_ROTATE_MUSIC, false)
        set(v) = sp.edit().putBoolean(KEY_ROTATE_MUSIC, v).apply()

    var debugLog: Boolean
        get() = sp.getBoolean(KEY_DEBUG_LOG, true)
        set(v) = sp.edit().putBoolean(KEY_DEBUG_LOG, v).apply()

    /** Last text that was sent to the car, shown in the settings screen. */
    var lastHint: String?
        get() = sp.getString(KEY_LAST_HINT, null)
        set(v) = sp.edit().putString(KEY_LAST_HINT, v).apply()

    fun isAppEnabled(app: NavApp) = enabled && app.key in enabledApps

    companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_APPS = "enabled_apps"
        const val KEY_MODE = "mode"
        const val KEY_ROTATE_MUSIC = "rotate_music"
        const val KEY_DEBUG_LOG = "debug_log"
        const val KEY_LAST_HINT = "last_hint"
    }
}
