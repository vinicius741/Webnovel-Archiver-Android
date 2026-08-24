package com.vinicius741.webnovelarchiver.tts

import com.vinicius741.webnovelarchiver.domain.model.TtsSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns the engine's canonical observable playback state so [TtsEngine] stays focused on playback
 * sequencing. The initial update is deliberately non-authoritative: the repository hydrates after
 * the process-wide engine is constructed, and an authoritative `null` must remain reserved for an
 * explicit stop.
 */
internal class TtsPlaybackPublisher {
    private val mutablePlaybackState = MutableStateFlow(TtsPlaybackUpdate(snapshot = null, isAuthoritative = false))

    /** Canonical observable playback state; authoritative `null` means playback explicitly stopped. */
    val playbackState: StateFlow<TtsPlaybackUpdate> = mutablePlaybackState.asStateFlow()

    /** Publishes a snapshot of the session now speaking ("paused" whenever [isPlaying] is false). */
    fun publish(
        session: TtsSession?,
        totalChunks: Int,
        isPlaying: Boolean,
    ) {
        mutablePlaybackState.value =
            TtsPlaybackUpdate(
                snapshot = TtsPlaybackState.snapshotForSession(session, totalChunks, isPlaying),
                isAuthoritative = true,
            )
    }

    /** Publishes the explicit stop that clears the transport, notification, and MediaSession. */
    fun stop() {
        mutablePlaybackState.value = TtsPlaybackUpdate(snapshot = null, isAuthoritative = true)
    }
}
