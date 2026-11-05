package com.vinicius741.webnovelarchiver.tts

import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener

/**
 * Forwards Android TTS utterance callbacks into the engine's mutex-serialized routes. Kept as a
 * named class so the routing surface is explicit.
 */
internal class TtsUtteranceProgressListener(
    private val onUtteranceError: (utteranceId: String?, errorCode: Int) -> Unit,
    private val onUtteranceDone: (utteranceId: String?) -> Unit,
    private val onWordStart: (utteranceId: String?, charStart: Int) -> Unit = { _, _ -> },
) : UtteranceProgressListener() {
    override fun onStart(utteranceId: String?) = Unit

    @Deprecated("Deprecated in Java")
    override fun onError(utteranceId: String?) = onUtteranceError(utteranceId, TextToSpeech.ERROR)

    override fun onError(
        utteranceId: String?,
        errorCode: Int,
    ) = onUtteranceError(utteranceId, errorCode)

    override fun onDone(utteranceId: String?) = onUtteranceDone(utteranceId)

    override fun onRangeStart(
        utteranceId: String?,
        start: Int,
        end: Int,
        frame: Int,
    ) = onWordStart(utteranceId, start)
}
