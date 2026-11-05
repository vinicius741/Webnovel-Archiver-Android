package com.vinicius741.webnovelarchiver.tts

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Renders with the Android [TextToSpeech] engine; always at 1x, since speed is applied at playback. */
internal class TtsFileSynthesizer(
    private val engine: () -> TextToSpeech?,
) : TtsSpeechSynthesizer {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val sequence = AtomicLong(0L)

    val progressListener: UtteranceProgressListener =
        object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) = complete(utteranceId, true)

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = complete(utteranceId, false)

            override fun onError(
                utteranceId: String?,
                errorCode: Int,
            ) = complete(utteranceId, false)
        }

    override suspend fun synthesize(
        text: String,
        output: File,
    ): Boolean {
        val tts = engine() ?: return false
        val id = "render_${sequence.incrementAndGet()}"
        val result = CompletableDeferred<Boolean>()
        pending[id] = result
        try {
            if (tts.synthesizeToFile(text, Bundle(), output, id) != TextToSpeech.SUCCESS) return false
            return withTimeoutOrNull(RENDER_TIMEOUT_MS) { result.await() } == true
        } finally {
            pending.remove(id)
        }
    }

    private fun complete(
        id: String?,
        success: Boolean,
    ) {
        pending.remove(id)?.complete(success)
    }

    private companion object {
        const val RENDER_TIMEOUT_MS = 60_000L
    }
}
