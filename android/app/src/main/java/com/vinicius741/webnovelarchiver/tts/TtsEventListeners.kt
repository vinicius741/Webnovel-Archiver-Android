package com.vinicius741.webnovelarchiver.tts

/**
 * Multicast registry for the event channels that are not durable playback state (errors and voice
 * availability). Playback state itself is exposed through [TtsEngine.playbackState].
 */
internal class TtsEventListeners {
    private val errors = mutableListOf<(TtsPlaybackError) -> Unit>()
    private val voices = mutableListOf<(List<VoiceInfo>) -> Unit>()

    fun addError(listener: (TtsPlaybackError) -> Unit) = addIdentity(errors, listener)

    fun removeError(listener: (TtsPlaybackError) -> Unit) = removeIdentity(errors, listener)

    fun addVoices(listener: (List<VoiceInfo>) -> Unit) = addIdentity(voices, listener)

    fun removeVoices(listener: (List<VoiceInfo>) -> Unit) = removeIdentity(voices, listener)

    fun dispatchError(error: TtsPlaybackError) {
        errors.toList().forEach { runCatching { it(error) } }
    }

    fun dispatchVoices(available: List<VoiceInfo>) {
        voices.toList().forEach { runCatching { it(available) } }
    }

    private fun <T> addIdentity(
        listeners: MutableList<T>,
        listener: T,
    ) {
        if (listeners.none { it === listener }) listeners.add(listener)
    }

    private fun <T> removeIdentity(
        listeners: MutableList<T>,
        listener: T,
    ) {
        listeners.removeAll { it === listener }
    }
}
