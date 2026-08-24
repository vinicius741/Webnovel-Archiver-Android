package com.vinicius741.webnovelarchiver.tts

import timber.log.Timber

/**
 * Utterance-level playback diagnostics. Extracted from [TtsEngine] so the engine stays within the
 * file-size budget; messages keep the "TTS" prefix so logcat filtering is unchanged. Per-chunk
 * entries log at DEBUG (invisible to the release log tree, which records WARN+ only), lifecycle
 * entries at INFO.
 */
internal object TtsEngineLogging {
    fun sessionStart(
        storyId: String,
        chapterId: String,
        chunkCount: Int,
        startIndex: Int,
        initialized: Boolean,
    ) {
        Timber.i(
            "TTS session start: story=%s chapter=%s chunks=%d startIndex=%d initialized=%b",
            storyId,
            chapterId,
            chunkCount,
            startIndex,
            initialized,
        )
    }

    fun engineInit(
        status: Int,
        pendingSpeak: Boolean,
        playbackActive: Boolean,
    ) {
        Timber.i("TTS engine onInit: status=%s pendingSpeak=%b playbackActive=%b", status, pendingSpeak, playbackActive)
    }

    fun speak(
        chunkIndex: Int,
        totalChunks: Int,
        utteranceId: String,
        spoken: String,
        result: Int,
    ) {
        Timber.d(
            "TTS speak: chunk %d/%d id=%s len=%d result=%d text=\"%s\"",
            chunkIndex,
            totalChunks,
            utteranceId,
            spoken.length,
            result,
            spoken.take(60),
        )
    }

    fun utteranceDone(
        utteranceId: String?,
        currentUtteranceId: String?,
    ) {
        Timber.d("TTS onDone: id=%s current=%s", utteranceId, currentUtteranceId)
    }
}
