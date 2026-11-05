package com.vinicius741.webnovelarchiver.tts

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns the engine's canonical observable playback state so [TtsEngine] stays focused on playback
 * sequencing. The initial update is deliberately non-authoritative: the repository hydrates after
 * the process-wide engine is constructed, and an authoritative `null` must remain reserved for an
 * explicit stop.
 */
internal class TtsPlaybackPublisher {
    private val mutablePlaybackState = MutableStateFlow(TtsPlaybackUpdate(snapshot = null, isAuthoritative = false))
    private val nextEventId = AtomicLong(0L)

    /** Canonical observable playback state; authoritative `null` means playback explicitly stopped. */
    val playbackState: StateFlow<TtsPlaybackUpdate> = mutablePlaybackState.asStateFlow()

    /** Publishes the session now loaded; a null snapshot here means the session is not resumable. */
    fun publish(snapshot: TtsPlaybackSnapshot?) {
        mutablePlaybackState.value =
            TtsPlaybackUpdate(
                snapshot = snapshot,
                isAuthoritative = true,
                eventId = nextEventId.incrementAndGet(),
            )
    }

    /** Publishes the explicit stop that clears the transport, notification, and MediaSession. */
    fun stop() {
        mutablePlaybackState.value =
            TtsPlaybackUpdate(
                snapshot = null,
                isAuthoritative = true,
                eventId = nextEventId.incrementAndGet(),
            )
    }
}
