package com.vinicius741.webnovelarchiver.tts

/**
 * Multicast observer registry shared by the Android TTS adapter, foreground service, and Reader.
 * Dispatch uses defensive snapshots so observers can remove themselves from inside callbacks.
 */
internal class TtsEventListeners {
    private val state = mutableListOf<(TtsPlaybackSnapshot?) -> Unit>()
    private val errors = mutableListOf<(TtsPlaybackError) -> Unit>()
    private val voices = mutableListOf<(List<VoiceInfo>) -> Unit>()

    fun addState(listener: (TtsPlaybackSnapshot?) -> Unit) = addIdentity(state, listener)

    fun removeState(listener: (TtsPlaybackSnapshot?) -> Unit) = removeIdentity(state, listener)

    fun addError(listener: (TtsPlaybackError) -> Unit) = addIdentity(errors, listener)

    fun removeError(listener: (TtsPlaybackError) -> Unit) = removeIdentity(errors, listener)

    fun addVoices(listener: (List<VoiceInfo>) -> Unit) = addIdentity(voices, listener)

    fun removeVoices(listener: (List<VoiceInfo>) -> Unit) = removeIdentity(voices, listener)

    fun dispatchState(snapshot: TtsPlaybackSnapshot?) {
        state.toList().forEach { runCatching { it(snapshot) } }
    }

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
