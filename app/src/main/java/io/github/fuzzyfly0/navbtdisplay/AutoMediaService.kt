package io.github.fuzzyfly0.navbtdisplay

import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.service.media.MediaBrowserService

/**
 * Android Auto as a *media* app: the "now playing" screen shows the current hint.
 *
 * Android Auto checks template apps ([CarHintService]) against the Play Store
 * and rejects sideloaded ones when that check fails (seen on GrapheneOS with
 * sandboxed Google Play). Media apps are not checked that way, so this entry
 * shows up in the launcher reliably.
 *
 * The session only exists while Android Auto (or another media browser) is
 * bound, never plays audio and stays paused, so it does not compete with the
 * Bluetooth display session of [NavPublisher].
 */
class AutoMediaService : MediaBrowserService() {
    private lateinit var session: MediaSession
    private var lastKey: String? = null
    private val listener: () -> Unit = ::publish

    override fun onCreate() {
        super.onCreate()
        session = MediaSession(this, "NavBtDisplayAuto").apply {
            setPlaybackState(
                PlaybackState.Builder()
                    .setState(PlaybackState.STATE_PAUSED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 0f)
                    .build(),
            )
            isActive = true
        }
        sessionToken = session.sessionToken
        LiveHint.addListener(listener)
        publish()
        DebugLog.log(this, "android auto media service created")
    }

    override fun onDestroy() {
        LiveHint.removeListener(listener)
        session.release()
        DebugLog.log(this, "android auto media service destroyed")
        super.onDestroy()
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot =
        BrowserRoot(ROOT_ID, null)

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowser.MediaItem>>) {
        // Nothing to browse; the hint is shown in the "now playing" view.
        result.sendResult(mutableListOf())
    }

    private fun publish() {
        val s = LiveHint.state
        val warning = s.warning
        val guidance = s.guidance
        val (title, artist, album) = when {
            warning != null -> Triple(warning.title, warning.artist, guidance?.title.orEmpty())
            guidance != null -> Triple(
                guidance.title.ifBlank { getString(R.string.car_guidance_running) },
                guidance.artist,
                guidance.album,
            )
            else -> Triple(getString(R.string.car_no_guidance), getString(R.string.car_no_guidance_hint), "")
        }
        val key = "$title|$artist|$album"
        if (key == lastKey) return
        lastKey = key
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, "hint-${key.hashCode()}")
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, album)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, artist)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION, album)
                .build(),
        )
    }

    private companion object {
        const val ROOT_ID = "root"
    }
}
