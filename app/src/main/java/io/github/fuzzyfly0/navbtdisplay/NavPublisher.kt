package io.github.fuzzyfly0.navbtdisplay

import android.content.ComponentName
import android.content.Context
import android.media.AudioAttributes
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
    private const val WARNING_MS = 10_000L

    /** The same warning text after this long without updates is a new warning (the next camera). */
    private const val WARNING_FORGET_MS = 30_000L
    private const val NAV_PHASE_MS = 6_000L
    private const val MUSIC_PHASE_MS = 3_000L
    private const val MIN_UPDATE_INTERVAL_MS = 1_000L
    private const val REDISPATCH_DELAY_MS = 500L
    private const val RECLAIM_DELAY_MS = 1_000L
    private const val RECLAIM_POLL_MS = 500L
    private const val RECLAIM_MAX_WAIT_MS = 15_000L

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

    /** A warning (speed camera) shown on top of the guidance, if any. */
    private var warning: CarText? = null
    private var lastWarningKey: String? = null
    private var lastWarningAt = 0L

    /** Rotation in TAKEOVER mode: whether the first line currently shows the music title. */
    private var showMusic = false
    private var musicController: MediaController? = null
    private var musicTrack: String? = null
    private var musicPlaying = false
    private var reclaimDeadline = 0L

    private val releaseRunnable = Runnable { if (warning == null) release("timer") }
    private val metadataRunnable = Runnable { pendingMetadata?.let(::applyMetadata) }
    private val warningEndRunnable = Runnable(::endWarning)
    private val rotateRunnable = Runnable(::rotate)
    private val reclaimRunnable = Runnable(::reclaim)

    private fun init(context: Context) {
        if (!::appContext.isInitialized) appContext = context.applicationContext
    }

    /** A guidance notification was posted or updated. */
    fun onGuidance(context: Context, notificationKey: String, car: CarText) {
        init(context)
        val newManeuver = car.maneuverKey != current?.maneuverKey
        guidanceKey = notificationKey
        current = car
        if (warning == null) Prefs(appContext).lastHint = hintText(car)

        when (Prefs(appContext).mode) {
            DisplayMode.PASSIVE -> {
                ensureSession()
                setPlaybackState(PlaybackState.STATE_PLAYING)
                show()
            }
            DisplayMode.TAKEOVER -> {
                if (newManeuver) suspendedManeuver = null
                if (suspendedManeuver != null) return
                // Re-claim on every new maneuver in case the music app (re)started
                // its own player in the meantime.
                if (newManeuver || session == null) claim()
                if (newManeuver) restartRotation()
                show()
            }
            DisplayMode.FLASH -> {
                if (newManeuver && warning == null) {
                    claim()
                    handler.removeCallbacks(releaseRunnable)
                    handler.postDelayed(releaseRunnable, FLASH_MS)
                }
                // Keep the distance counting down while the hint is visible.
                if (silence?.isPlaying == true) show()
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
        if (warning == null) release("guidance ended")
    }

    /**
     * A warning app posted or updated a warning. It is shown for [WARNING_MS] in every
     * mode, also without guidance; updates of the same warning only refresh the text.
     */
    fun onWarning(context: Context, notificationKey: String, car: CarText) {
        init(context)
        val now = SystemClock.elapsedRealtime()
        val isNew = car.maneuverKey != lastWarningKey || now - lastWarningAt > WARNING_FORGET_MS
        lastWarningKey = car.maneuverKey
        lastWarningAt = now
        if (!isNew && warning == null) return
        warning = car
        Prefs(appContext).lastHint = hintText(car)
        if (isNew) {
            DebugLog.log(appContext, "show warning from $notificationKey")
            if (Prefs(appContext).mode == DisplayMode.PASSIVE) {
                ensureSession()
                setPlaybackState(PlaybackState.STATE_PLAYING)
            } else {
                claim()
            }
            handler.removeCallbacks(warningEndRunnable)
            handler.postDelayed(warningEndRunnable, WARNING_MS)
        }
        show()
    }

    /** Re-applies settings that take effect while the display is held (music rotation). */
    fun refresh(context: Context) {
        init(context)
        if (session == null) return
        restartRotation()
        show()
    }

    /** Shows a fixed test hint for a few seconds, to check the car display without navigating. */
    fun showTest(context: Context) {
        init(context)
        takeOver()
        setMetadata(
            CarText(
                title = appContext.getString(R.string.test_hint_title),
                artist = appContext.getString(R.string.test_hint_artist),
                album = appContext.getString(R.string.test_hint_album),
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
        warning = null
        handler.removeCallbacks(warningEndRunnable)
        release("stopped")
    }

    private fun endWarning() {
        warning = null
        val car = current
        when {
            car == null -> release("warning ended")
            Prefs(appContext).mode == DisplayMode.FLASH -> release("warning ended")
            suspendedManeuver != null -> release("warning ended")
            else -> {
                Prefs(appContext).lastHint = hintText(car)
                show()
            }
        }
    }

    /**
     * Takes the display, but does not restart our player while the navigation app
     * speaks: some phones and head units cut the announcement when a new player starts.
     */
    private fun claim() {
        if (session == null || !navVoiceActive()) {
            takeOver()
        } else {
            requestReclaim("voice prompt running")
        }
    }

    private fun takeOver() {
        ensureSession()
        session?.isActive = true
        val s = silence ?: SilentAudio(appContext).also { silence = it }
        s.restart()
        setPlaybackState(PlaybackState.STATE_PLAYING)
        handler.removeCallbacks(reclaimRunnable)
        attachMusic()
        DebugLog.log(appContext, "take over display")
    }

    /** Restarts our player after a short delay, once no voice prompt is playing. */
    private fun requestReclaim(reason: String) {
        DebugLog.log(appContext, "re-claim display soon ($reason)")
        reclaimDeadline = SystemClock.elapsedRealtime() + RECLAIM_MAX_WAIT_MS
        handler.removeCallbacks(reclaimRunnable)
        handler.postDelayed(reclaimRunnable, RECLAIM_DELAY_MS)
    }

    private fun reclaim() {
        val s = silence ?: return
        if (session == null) return
        if (navVoiceActive() && SystemClock.elapsedRealtime() < reclaimDeadline) {
            handler.postDelayed(reclaimRunnable, RECLAIM_POLL_MS)
            return
        }
        s.restart()
        setPlaybackState(PlaybackState.STATE_PLAYING)
        DebugLog.log(appContext, "re-claimed display")
        show()
    }

    private fun navVoiceActive(): Boolean = runCatching {
        appContext.getSystemService(AudioManager::class.java).activePlaybackConfigurations.any {
            it.audioAttributes.usage == AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
        }
    }.getOrDefault(false)

    private fun release(reason: String) {
        handler.removeCallbacks(releaseRunnable)
        handler.removeCallbacks(metadataRunnable)
        handler.removeCallbacks(rotateRunnable)
        handler.removeCallbacks(reclaimRunnable)
        pendingMetadata = null
        showMusic = false
        detachMusic()
        silence?.stop()
        session?.let {
            it.setPlaybackState(state(PlaybackState.STATE_STOPPED))
            it.isActive = false
            it.release()
            DebugLog.log(appContext, "hand display back ($reason)")
        }
        session = null
    }

    /** Puts the current warning or guidance (with the music title, if rotating) on the display. */
    private fun show() {
        warning?.let {
            setMetadata(it)
            return
        }
        val car = current ?: return
        val music = if (showMusic && rotationEnabled()) musicLine() else null
        setMetadata(if (music != null) car.copy(title = music) else car)
    }

    private fun rotationEnabled(): Boolean {
        val prefs = Prefs(appContext)
        return prefs.rotateMusic && prefs.mode == DisplayMode.TAKEOVER
    }

    /** A new maneuver always starts with the navigation phase. */
    private fun restartRotation() {
        showMusic = false
        handler.removeCallbacks(rotateRunnable)
        if (rotationEnabled()) handler.postDelayed(rotateRunnable, NAV_PHASE_MS)
    }

    private fun rotate() {
        if (session == null || !rotationEnabled()) {
            showMusic = false
            return
        }
        showMusic = !showMusic && warning == null && musicLine() != null
        show()
        handler.postDelayed(rotateRunnable, if (showMusic) MUSIC_PHASE_MS else NAV_PHASE_MS)
    }

    private fun musicLine(): String? {
        val c = musicController ?: return null
        if (c.playbackState?.state != PlaybackState.STATE_PLAYING) return null
        val md = c.metadata ?: return null
        val text = listOf(MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_TITLE)
            .mapNotNull { md.getString(it)?.trim()?.takeIf(String::isNotEmpty) }
            .joinToString(" – ")
        return text.takeIf { it.isNotEmpty() }?.let { "♪ $it" }
    }

    /**
     * Watches the music app while we hold the display. A new track or a resumed
     * playback usually means the music app started a new player and took the
     * display, so we take it back right away instead of waiting for the next maneuver.
     */
    private val musicCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            val track = trackOf(metadata)
            if (track == musicTrack) return
            musicTrack = track
            onMusicChanged("track changed")
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            val playing = state?.state == PlaybackState.STATE_PLAYING
            if (playing == musicPlaying) return
            musicPlaying = playing
            if (playing) onMusicChanged("music started")
        }

        override fun onSessionDestroyed() = detachController()
    }

    private var watchingSessions = false
    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { watchMusicController(notify = true) }

    private fun onMusicChanged(reason: String) {
        if (session == null || silence?.isPlaying != true) return
        if (suspendedManeuver != null && warning == null) return
        requestReclaim(reason)
    }

    private fun attachMusic() {
        if (!watchingSessions) {
            watchingSessions = runCatching {
                appContext.getSystemService(MediaSessionManager::class.java)
                    .addOnActiveSessionsChangedListener(sessionsListener, listenerComponent(), handler)
            }.isSuccess
        }
        watchMusicController(notify = false)
    }

    private fun watchMusicController(notify: Boolean) {
        val found = findMusicController()
        if (found?.sessionToken == musicController?.sessionToken) return
        detachController()
        found ?: return
        musicController = found
        musicTrack = trackOf(found.metadata)
        musicPlaying = found.playbackState?.state == PlaybackState.STATE_PLAYING
        found.registerCallback(musicCallback, handler)
        DebugLog.log(appContext, "watching music session of ${found.packageName}")
        if (notify && musicPlaying) onMusicChanged("music app changed")
    }

    private fun trackOf(md: MediaMetadata?) = md?.let {
        "${it.getString(MediaMetadata.METADATA_KEY_ARTIST)}|${it.getString(MediaMetadata.METADATA_KEY_TITLE)}"
    }

    private fun detachController() {
        musicController?.unregisterCallback(musicCallback)
        musicController = null
    }

    private fun detachMusic() {
        detachController()
        if (!watchingSessions) return
        watchingSessions = false
        runCatching {
            appContext.getSystemService(MediaSessionManager::class.java)
                .removeOnActiveSessionsChangedListener(sessionsListener)
        }
    }

    private fun hintText(car: CarText) =
        listOf(car.title, car.artist, car.album).filter { it.isNotBlank() }.joinToString("\n")

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

    private fun listenerComponent() = ComponentName(appContext, NavListenerService::class.java)

    private fun findMusicController(): MediaController? = runCatching {
        val msm = appContext.getSystemService(MediaSessionManager::class.java)
        val sessions = msm.getActiveSessions(listenerComponent())
            .filter { it.packageName != appContext.packageName }
        sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: sessions.firstOrNull()
    }.getOrNull()
}
