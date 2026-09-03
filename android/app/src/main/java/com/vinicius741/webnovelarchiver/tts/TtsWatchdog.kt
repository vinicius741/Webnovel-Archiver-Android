package com.vinicius741.webnovelarchiver.tts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

internal class TtsWatchdog(
    private val scope: CoroutineScope,
    private val onRetry: (utteranceId: String, chunkIndex: Int) -> Unit,
    private val onStalled: (utteranceId: String) -> Unit,
) {
    private var job: Job? = null
    var chunkIndex: Int = -1
    var retryCount: Int = 0

    fun schedule(
        utteranceId: String,
        text: String,
        rate: Float,
        fallbackChunkIndex: Int,
    ) {
        cancel()
        val timeoutMs = TtsWatchdogPlanning.timeoutMs(text.length, rate)
        job =
            scope.launch {
                delay(timeoutMs)
                if (retryCount == 0) {
                    retryCount += 1
                    Timber.w("TTS stalled for utterance %s; retrying chunk %s", utteranceId, fallbackChunkIndex)
                    cancel()
                    onRetry(utteranceId, fallbackChunkIndex)
                } else {
                    onStalled(utteranceId)
                }
            }
    }

    fun reset() {
        chunkIndex = -1
        retryCount = 0
        cancel()
    }

    fun cancel() {
        job?.cancel()
        job = null
    }
}
