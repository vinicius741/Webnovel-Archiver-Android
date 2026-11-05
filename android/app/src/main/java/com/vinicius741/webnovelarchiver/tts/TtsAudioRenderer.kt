package com.vinicius741.webnovelarchiver.tts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File

/**
 * Whatever turns text into audio. The Android engine implements it today; another voice source only
 * has to write a mono 16-bit WAV to [output].
 */
internal fun interface TtsSpeechSynthesizer {
    suspend fun synthesize(
        text: String,
        output: File,
    ): Boolean
}

internal data class TtsRenderRequest(
    val key: String,
    val text: String,
)

/** Works through an ordered plan of sentences one at a time; a new plan replaces the old at once. */
internal class TtsAudioRenderer(
    private val cache: TtsAudioCache,
    private val synthesizer: TtsSpeechSynthesizer,
    private val scope: CoroutineScope,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onRendered(
            request: TtsRenderRequest,
            durationMs: Long,
        )

        /** The engine could not produce audio, so this output cannot continue. */
        fun onRenderFailed(request: TtsRenderRequest)

        fun onPlanFinished()
    }

    private val queue = ArrayDeque<TtsRenderRequest>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var worker: Job? = null
    private var consecutiveEmpty = 0
    private var planPending = false

    fun setPlan(plan: List<TtsRenderRequest>) {
        synchronized(queue) {
            queue.clear()
            queue.addAll(plan)
            planPending = true
        }
        wake.trySend(Unit)
        if (worker?.isActive != true) worker = scope.launch { runQueue() }
    }

    fun stop() {
        synchronized(queue) { queue.clear() }
        worker?.cancel()
        worker = null
    }

    private suspend fun runQueue() {
        while (scope.isActive) {
            val next = synchronized(queue) { queue.removeFirstOrNull() }
            if (next == null) {
                val justFinished = synchronized(queue) { planPending.also { planPending = false } }
                if (justFinished) callbacks.onPlanFinished()
                wake.receive()
            } else {
                renderOne(next)
            }
        }
    }

    private suspend fun renderOne(request: TtsRenderRequest) {
        cache.durationMs(request.key)?.let { cached ->
            callbacks.onRendered(request, cached)
            return
        }
        val temporary = cache.temporaryFile(request.key)
        // One retry: a single hiccup must not give up on rendering for the whole session.
        val succeeded = synthesizer.synthesize(request.text, temporary) || synthesizer.synthesize(request.text, temporary)
        val clip =
            if (succeeded) {
                runCatching { temporary.takeIf { it.isFile }?.readBytes() }.getOrNull()?.let(TtsPcmPlanning::parseWav)
            } else {
                null
            }
        temporary.delete()
        if (!succeeded) {
            Timber.w("TTS render failed for a %d character sentence", request.text.length)
            reportFailure(request)
            return
        }
        // Unspeakable text legitimately renders nothing; an engine producing only nothing is broken.
        val trimmed = clip?.let(TtsPcmPlanning::trimSilence) ?: TtsPcmClip(DEFAULT_SAMPLE_RATE, ByteArray(0))
        consecutiveEmpty = if (trimmed.pcm.isEmpty()) consecutiveEmpty + 1 else 0
        if (consecutiveEmpty >= MAX_CONSECUTIVE_EMPTY) {
            Timber.w("TTS render produced no audio %d times in a row", consecutiveEmpty)
            reportFailure(request)
            return
        }
        runCatching { cache.write(request.key, trimmed) }
            .onSuccess { callbacks.onRendered(request, trimmed.durationMs) }
            .onFailure {
                Timber.w(it, "TTS audio cache write failed")
                reportFailure(request)
            }
    }

    private fun reportFailure(request: TtsRenderRequest) {
        synchronized(queue) {
            queue.clear()
            planPending = false
        }
        callbacks.onRenderFailed(request)
    }

    private companion object {
        const val DEFAULT_SAMPLE_RATE = 24_000
        const val MAX_CONSECUTIVE_EMPTY = 5
    }
}
