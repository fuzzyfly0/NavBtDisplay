package io.github.fuzzyfly0.navbtdisplay

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent

/**
 * Publishes navigation hints as "now playing" metadata, which the Bluetooth
 * AVRCP stack forwards to the car stereo.
 *
 * The car shows only the *addressed* player, which Android chooses as the
 * media button session: the app that most recently started audio playback.
 * To get on the display we therefore play silence ([SilentAudio]); to hand the
 * display back we stop it and release our session, so Android falls back to
 * the music app. See README for the trade-offs of the [DisplayMode]s.
 *
 * All methods must be called on the main thread.
 */
object NavPublisher {
    private const val FLASH_MS = 8_000L
    private const val TEST_MS = 10_000L
    private const val MIN_UPDATE_INTERVAL_MS = 1_000L
    private const val REDISPATCH_DELAY_MS = 500L

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var appContext: Context
    private var session: MediaSession? = null
    private var silence: SilentAudio? = null

    /** Notification key of the guidance notification currently shown, if any. */
    private var guidanceKey: String? = null
    private var current: CarText? = null

    /** In TAKEOVER mode: the user pressed a car button, stay away until the maneuver changes. */
    private var suspendedManeuver: String? = null
    private var lastMetadataAt = 0L
    private var pendingMetadata: CarText? = null

    private val releaseRunnable = Runnable { release("timer") }
    private val metadataRunnable = Runnable { pendingMetadata?.let(::applyMetadata) }

    private fun init(context: Context) {
        if (!::appContext.isInitialized) appContext = context.applicationContext
    }

    /** A guidance notification was posted or updated. */
    fun onGuidance(context: Context, notificationKey: String, car: CarText) {
        init(context)
        val newManeuver = car.maneuverKey != current?.maneuverKey
        guidanceKey = notificationKey
        current = car
        Prefs(appContext).lastHint = "${car.title}\n${car.artist}"

        when (Prefs(appContext).mode) {
            DisplayMode.PASSIVE -> {
                ensureSession()
                setPlaybackState(PlaybackState.STATE_PLAYING)
                setMetadata(car)
            }
            DisplayMode.TAKEOVER -> {
                if (newManeuver) suspendedManeuver = null
                if (suspendedManeuver != null) return
                // A new player on every new maneuver re-claims the display in case
                // the music app (re)started its own player in the meantime.
                if (newManeuver || session == null) takeOver()
                setMetadata(car)
            }
            DisplayMode.FLASH -> {
                if (newManeuver) {
                    takeOver()
                    handler.removeCallbacks(releaseRunnable)
                    handler.postDelayed(releaseRunnable, FLASH_MS)
                }
                // Keep the distance counting down while the hint is visible.
                if (silence?.isPlaying == true) setMetadata(car)
            }
        }
    }

    /** The guidance notification was removed: navigation has ended. */
    fun onGuidanceEnded(context: Context, notificationKey: String) {
        init(context)
        if (notificationKey != guidanceKey) return
        guidanceKey = null
        current = null
        suspendedManeuver = null
        release("guidance ended")
    }

    /** Shows a fixed test hint for a few seconds, to check the car display without navigating. */
    fun showTest(context: Context) {
        init(context)
        takeOver()
        setMetadata(
            CarText(
                title = appContext.getString(R.string.test_hint_title),
                artist = appContext.getString(R.string.app_name),
                album = appContext.getString(R.string.app_name),
                maneuverKey = "test",
            ),
        )
        handler.removeCallbacks(releaseRunnable)
        handler.postDelayed(releaseRunnable, TEST_MS)
    }

    /** Stops everything, e.g. when the app is switched off. */
    fun stop(context: Context) {
        init(context)
        guidanceKey = null
        current = null
        suspendedManeuver = null
        release("stopped")
    }

    private fun takeOver() {
        ensureSession()
        session?.isActive = true
        val s = silence ?: SilentAudio(appContext).also { silence = it }
        s.restart()
        setPlaybackState(PlaybackState.STATE_PLAYING)
        DebugLog.log(appContext, "take over display")
    }

    private fun release(reason: String) {
        handler.removeCallbacks(releaseRunnable)
        handler.removeCallbacks(metadataRunnable)
        pendingMetadata = null
        silence?.stop()
        session?.let {
            it.setPlaybackState(state(PlaybackState.STATE_STOPPED))
            it.isActive = false
            it.release()
            DebugLog.log(appContext, "hand display back ($reason)")
        }
        session = null
    }

    private fun ensureSession() {
        if (session != null) return
        session = MediaSession(appContext, "NavBtDisplay").apply {
            setCallback(callback, handler)
            setPlaybackState(state(PlaybackState.STATE_PAUSED))
            isActive = true
        }
    }

    private fun setPlaybackState(state: Int) {
        session?.setPlaybackState(state(state))
    }

    private fun state(state: Int): PlaybackState = PlaybackState.Builder()
        .setActions(
            PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP or
                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS,
        )
        .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
        .build()

    /** Throttled: some head units choke on very frequent metadata changes. */
    private fun setMetadata(car: CarText) {
        val wait = lastMetadataAt + MIN_UPDATE_INTERVAL_MS - SystemClock.elapsedRealtime()
        if (wait <= 0) {
            applyMetadata(car)
        } else {
            pendingMetadata = car
            handler.removeCallbacks(metadataRunnable)
            handler.postDelayed(metadataRunnable, wait)
        }
    }

    private fun applyMetadata(car: CarText) {
        pendingMetadata = null
        val s = session ?: return
        lastMetadataAt = SystemClock.elapsedRealtime()
        s.setMetadata(
            MediaMetadata.Builder()
                // A new media id per maneuver lets head units treat it as a new "track".
                .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, "nav-${car.maneuverKey.hashCode()}")
                .putString(MediaMetadata.METADATA_KEY_TITLE, car.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, car.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, car.album)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, car.title)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, car.artist)
                .build(),
        )
    }

    /**
     * Car buttons now reach *our* session. Forward them to the music app if we can
     * see its session (same Android profile only); otherwise give the display back
     * and re-send the key, so it reaches whichever app the system picks next.
     */
    private val callback = object : MediaSession.Callback() {
        override fun onPlay() = forward(KeyEvent.KEYCODE_MEDIA_PLAY) { it.play() }
        override fun onPause() = forward(KeyEvent.KEYCODE_MEDIA_PAUSE) { it.pause() }
        override fun onStop() = forward(KeyEvent.KEYCODE_MEDIA_STOP) { it.stop() }
        override fun onSkipToNext() = forward(KeyEvent.KEYCODE_MEDIA_NEXT) { it.skipToNext() }
        override fun onSkipToPrevious() =
            forward(KeyEvent.KEYCODE_MEDIA_PREVIOUS) { it.skipToPrevious() }
    }

    private fun forward(keyCode: Int, action: (MediaController.TransportControls) -> Unit) {
        val target = findMusicController()
        if (target != null) {
            DebugLog.log(appContext, "forward key $keyCode to ${target.packageName}")
            action(target.transportControls)
            return
        }
        DebugLog.log(appContext, "no music session visible, hand back and re-dispatch key $keyCode")
        suspendedManeuver = current?.maneuverKey ?: "suspended"
        release("car button")
        handler.postDelayed({
            val am = appContext.getSystemService(AudioManager::class.java)
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }, REDISPATCH_DELAY_MS)
    }

    private fun findMusicController(): MediaController? = runCatching {
        val msm = appContext.getSystemService(MediaSessionManager::class.java)
        val sessions = msm.getActiveSessions(ComponentName(appContext, NavListenerService::class.java))
            .filter { it.packageName != appContext.packageName }
        sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: sessions.firstOrNull()
    }.getOrNull()
}
