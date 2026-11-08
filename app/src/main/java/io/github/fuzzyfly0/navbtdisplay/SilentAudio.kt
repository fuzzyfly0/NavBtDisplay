package io.github.fuzzyfly0.navbtdisplay

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * Plays an endless loop of digital silence.
 *
 * Why: Android picks the "media button session" (which the Bluetooth AVRCP
 * stack reports to the car as the current player) from the app that most
 * recently *started an audio player* (frameworks/base MediaSessionStack +
 * AudioPlayerStateMonitor). A media session alone, without audio, never wins
 * against a music app that is actually playing.
 *
 * No audio focus is requested, so the music app keeps playing unchanged.
 */
class SilentAudio(private val context: Context) {
    private var track: AudioTrack? = null

    val isPlaying get() = track != null

    /** Starts a *new* player, which moves this app to the front of the system's playback order. */
    fun restart() {
        stop()
        val frames = SAMPLE_RATE / 2
        val bytes = frames * 2
        val t = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(bytes)
                .build()
                .also {
                    it.write(ByteArray(bytes), 0, bytes)
                    it.setLoopPoints(0, frames, -1)
                    it.play()
                }
        }.onFailure { DebugLog.log(context, "silent audio failed: $it") }.getOrNull()
        track = t
    }

    fun stop() {
        track?.let {
            runCatching { it.stop() }
            it.release()
        }
        track = null
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
    }
}
