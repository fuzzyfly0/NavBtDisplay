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

/** Where the album art sent to the car comes from. */
enum class ArtMode {
    OFF,

    /** The maneuver arrow (large icon / picture) of the navigation notification. */
    NOTIFICATION,

    /** A screenshot via screen sharing (MediaProjection), refreshed every few seconds. */
    SCREEN,
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

    /** Restart the silent player on every new maneuver; off: only after the music app took the display. */
    var reclaimOnManeuver: Boolean
        get() = sp.getBoolean(KEY_RECLAIM_ON_MANEUVER, true)
        set(v) = sp.edit().putBoolean(KEY_RECLAIM_ON_MANEUVER, v).apply()

    /** Report every new maneuver to the car as a new track (new media id). */
    var newTrackPerManeuver: Boolean
        get() = sp.getBoolean(KEY_NEW_TRACK_PER_MANEUVER, true)
        set(v) = sp.edit().putBoolean(KEY_NEW_TRACK_PER_MANEUVER, v).apply()

    var artMode: ArtMode
        get() = runCatching { ArtMode.valueOf(sp.getString(KEY_ART_MODE, null)!!) }.getOrDefault(ArtMode.OFF)
        set(v) = sp.edit().putString(KEY_ART_MODE, v.name).apply()

    /** How often a new screenshot is sent in [ArtMode.SCREEN]. */
    var artIntervalSec: Int
        get() = sp.getInt(KEY_ART_INTERVAL, 5)
        set(v) = sp.edit().putInt(KEY_ART_INTERVAL, v).apply()

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
        const val KEY_RECLAIM_ON_MANEUVER = "reclaim_on_maneuver"
        const val KEY_NEW_TRACK_PER_MANEUVER = "new_track_per_maneuver"
        const val KEY_ART_MODE = "art_mode"
        const val KEY_ART_INTERVAL = "art_interval"
        const val KEY_DEBUG_LOG = "debug_log"
        const val KEY_LAST_HINT = "last_hint"
    }
}
