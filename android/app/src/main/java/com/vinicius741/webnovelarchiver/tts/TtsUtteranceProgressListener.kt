package com.vinicius741.webnovelarchiver.tts

import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener

/**
 * Forwards Android TTS utterance callbacks into the engine's mutex-serialized routes. Kept as a
 * named class so [TtsEngine.onInit] stays readable and the routing surface is explicit.
 */
internal class TtsUtteranceProgressListener(
    private val onUtteranceError: (utteranceId: String?, errorCode: Int) -> Unit,
    private val onUtteranceDone: (utteranceId: String?) -> Unit,
) : UtteranceProgressListener() {
    override fun onStart(utteranceId: String?) = Unit

    @Deprecated("Deprecated in Java")
    override fun onError(utteranceId: String?) = onUtteranceError(utteranceId, TextToSpeech.ERROR)

    override fun onError(
        utteranceId: String?,
        errorCode: Int,
    ) = onUtteranceError(utteranceId, errorCode)

    override fun onDone(utteranceId: String?) = onUtteranceDone(utteranceId)
}
