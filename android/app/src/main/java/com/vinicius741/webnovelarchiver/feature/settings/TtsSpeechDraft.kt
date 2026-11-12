package com.vinicius741.webnovelarchiver.feature.settings

import com.vinicius741.webnovelarchiver.domain.model.TtsSettings

/**
 * The Voice & Speech values edited on screen and saved from the Save bar: rate, pitch, the behavior
 * switches and the pauses. Engine, voice and profiles save straight away, so they are never part of
 * the draft; [applyTo] lays the draft over the latest saved settings, leaving those untouched.
 */
internal data class TtsSpeechDraft(
    val rate: Float,
    val pitch: Float,
    val contextReplay: Boolean,
    val announceChapters: Boolean,
    val chapterSkipButtons: Boolean,
    val phrasePauseMs: Long,
    val sentencePauseMs: Long,
    val paragraphPauseMs: Long,
    val scenePauseMs: Long,
) {
    fun applyTo(saved: TtsSettings): TtsSettings =
        saved.copy(
            rate = rate,
            pitch = pitch,
            contextReplay = contextReplay,
            announceChapters = announceChapters,
            chapterSkipButtons = chapterSkipButtons,
            phrasePauseMs = phrasePauseMs,
            sentencePauseMs = sentencePauseMs,
            paragraphPauseMs = paragraphPauseMs,
            scenePauseMs = scenePauseMs,
        )

    companion object {
        fun of(settings: TtsSettings): TtsSpeechDraft =
            TtsSpeechDraft(
                rate = settings.rate,
                pitch = settings.pitch,
                contextReplay = settings.contextReplay,
                announceChapters = settings.announceChapters,
                chapterSkipButtons = settings.chapterSkipButtons,
                phrasePauseMs = settings.phrasePauseMs,
                sentencePauseMs = settings.sentencePauseMs,
                paragraphPauseMs = settings.paragraphPauseMs,
                scenePauseMs = settings.scenePauseMs,
            )
    }
}
