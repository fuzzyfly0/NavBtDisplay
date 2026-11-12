package io.github.fuzzyfly0.navbtdisplay

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
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

    /** Guards against apps whose maneuver text changes on every update. */
    private const val MIN_CLAIM_INTERVAL_MS = 5_000L
    private const val VOICE_POLL_MS = 500L

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
    private var lastTakeOverAt = 0L
    private var rotationRunning = false

    /** Album art; [artVersion] goes into the media id, because cars only fetch art for a new track. */
    private var art: Bitmap? = null
    private var artVersion = 0
    private var sentMediaId: String? = null
    private var sentArt: Bitmap? = null

    private var audioLogged: String? = null
    private var audioCallbackRegistered = false

    private val releaseRunnable = Runnable { if (warning == null) release("timer") }
    private val voiceRetryRunnable = Runnable(::show)
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
                // Re-claim on new maneuvers in case the music app (re)started its own
                // player in the meantime; the music watcher covers the rest.
                val sinceClaim = SystemClock.elapsedRealtime() - lastTakeOverAt
                if (session == null) {
                    claim()
                } else if (newManeuver && Prefs(appContext).reclaimOnManeuver && sinceClaim >= MIN_CLAIM_INTERVAL_MS) {
                    claim()
                }
                // Back to the maneuver at once, but don't restart a running navigation phase:
                // apps whose text changes on every update would otherwise never show the music.
                if (newManeuver && (showMusic || !rotationRunning)) restartRotation()
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

    /** Re-applies settings that take effect while the display is held (music rotation, album art). */
    fun refresh(context: Context) {
        init(context)
        if (Prefs(appContext).artMode == ArtMode.OFF) art = null
        if (session == null) return
        restartRotation()
        show()
    }

    /** New album art from [source]; ignored unless that source is selected. */
    fun onArt(context: Context, source: ArtMode, bitmap: Bitmap?) {
        init(context)
        if (Prefs(appContext).artMode != source) return
        val old = art
        if (bitmap == old || (bitmap != null && old != null && bitmap.sameAs(old))) return
        art = bitmap
        artVersion++
        if (session != null) show()
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
        lastTakeOverAt = SystemClock.elapsedRealtime()
        handler.removeCallbacks(reclaimRunnable)
        attachMusic()
        watchAudioPlayers()
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
        lastTakeOverAt = SystemClock.elapsedRealtime()
        DebugLog.log(appContext, "re-claimed display")
        show()
    }

    /** A navigation prompt, or other speech that is not media (podcasts are USAGE_MEDIA). */
    private fun isVoicePrompt(a: AudioAttributes) =
        a.usage == AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE ||
            (a.contentType == AudioAttributes.CONTENT_TYPE_SPEECH && a.usage != AudioAttributes.USAGE_MEDIA)

    private fun navVoiceActive(): Boolean = runCatching {
        appContext.getSystemService(AudioManager::class.java).activePlaybackConfigurations.any {
            isVoicePrompt(it.audioAttributes)
        }
    }.getOrDefault(false)

    /** Logs which players are active (usage/content type), to find out how an app plays its prompts. */
    private val audioCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>) {
            val desc = configs.joinToString(", ") {
                val a = it.audioAttributes
                "usage=${a.usage} content=${a.contentType}" + if (isVoicePrompt(a)) " (prompt)" else ""
            }.ifEmpty { "none" }
            if (desc == audioLogged) return
            audioLogged = desc
            DebugLog.log(appContext, "audio players: $desc")
        }
    }

    private fun watchAudioPlayers() {
        if (audioCallbackRegistered) return
        audioCallbackRegistered = runCatching {
            appContext.getSystemService(AudioManager::class.java).registerAudioPlaybackCallback(audioCallback, handler)
        }.isSuccess
    }

    private fun unwatchAudioPlayers() {
        if (!audioCallbackRegistered) return
        audioCallbackRegistered = false
        audioLogged = null
        runCatching { appContext.getSystemService(AudioManager::class.java).unregisterAudioPlaybackCallback(audioCallback) }
    }

    private fun release(reason: String) {
        handler.removeCallbacks(releaseRunnable)
        handler.removeCallbacks(metadataRunnable)
        handler.removeCallbacks(rotateRunnable)
        handler.removeCallbacks(reclaimRunnable)
        handler.removeCallbacks(voiceRetryRunnable)
        pendingMetadata = null
        showMusic = false
        rotationRunning = false
        sentMediaId = null
        sentArt = null
        detachMusic()
        unwatchAudioPlayers()
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
        rotationRunning = rotationEnabled()
        if (rotationRunning) handler.postDelayed(rotateRunnable, NAV_PHASE_MS)
    }

    private fun rotate() {
        if (session == null || !rotationEnabled()) {
            showMusic = false
            rotationRunning = false
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
        val prefs = Prefs(appContext)
        val artOn = prefs.artMode != ArtMode.OFF
        // A new media id makes head units treat it as a new "track": per maneuver (optional)
        // and per album art image, since cars only fetch the art of a new track.
        val base = if (prefs.newTrackPerManeuver) "nav-${car.maneuverKey.hashCode()}" else "nav"
        val wantedId = if (artOn) "$base-$artVersion" else base
        val wantedArt = if (artOn) art else null
        // Some head units mute briefly on a track change: no new track during a voice prompt.
        val hold = sentMediaId != null && wantedId != sentMediaId && navVoiceActive()
        val id = if (hold) sentMediaId!! else wantedId
        val bitmap = if (hold) sentArt else wantedArt
        handler.removeCallbacks(voiceRetryRunnable)
        if (hold) handler.postDelayed(voiceRetryRunnable, VOICE_POLL_MS)
        sentMediaId = id
        sentArt = bitmap
        s.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, id)
                .putString(MediaMetadata.METADATA_KEY_TITLE, car.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, car.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, car.album)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, car.title)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, car.artist)
                .apply {
                    if (bitmap != null) {
                        putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, bitmap)
                        putBitmap(MediaMetadata.METADATA_KEY_ART, bitmap)
                    }
                }
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
