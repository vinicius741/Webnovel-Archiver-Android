package com.vinicius741.webnovelarchiver.tts

import android.speech.tts.TextToSpeech
import kotlin.math.roundToLong

enum class TtsPlaybackErrorKind {
    InitFailed,
    LanguageMissingData,
    LanguageNotSupported,
    VoiceUnavailable,
    VoiceRejected,
    SpeakFailed,
    SynthesisFailed,
    Stalled,
}

data class TtsPlaybackError(
    val kind: TtsPlaybackErrorKind,
    val code: Int? = null,
    val detail: String? = null,
)

object TtsErrorPlanning {
    fun synthesisCodeName(code: Int?): String =
        when (code) {
            TextToSpeech.ERROR -> "ERROR"
            TextToSpeech.ERROR_INVALID_REQUEST -> "ERROR_INVALID_REQUEST"
            TextToSpeech.ERROR_NETWORK -> "ERROR_NETWORK"
            TextToSpeech.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
            TextToSpeech.ERROR_NOT_INSTALLED_YET -> "ERROR_NOT_INSTALLED_YET"
            TextToSpeech.ERROR_OUTPUT -> "ERROR_OUTPUT"
            TextToSpeech.ERROR_SERVICE -> "ERROR_SERVICE"
            TextToSpeech.ERROR_SYNTHESIS -> "ERROR_SYNTHESIS"
            null -> "ERROR_UNKNOWN"
            else -> "ERROR_$code"
        }

    fun logMessage(error: TtsPlaybackError): String {
        val suffix =
            error.detail
                ?.takeIf { it.isNotBlank() }
                ?.let { ": $it" }
                .orEmpty()
        return when (error.kind) {
            TtsPlaybackErrorKind.InitFailed -> "TTS initialization failed$suffix"
            TtsPlaybackErrorKind.LanguageMissingData -> "TTS language data is missing$suffix"
            TtsPlaybackErrorKind.LanguageNotSupported -> "TTS language is not supported$suffix"
            TtsPlaybackErrorKind.VoiceUnavailable -> "Selected TTS voice is unavailable$suffix"
            TtsPlaybackErrorKind.VoiceRejected -> "Selected TTS voice was rejected$suffix"
            TtsPlaybackErrorKind.SpeakFailed -> "TTS speak() returned ERROR$suffix"
            TtsPlaybackErrorKind.SynthesisFailed -> "TTS synthesis failed (${synthesisCodeName(error.code)})$suffix"
            TtsPlaybackErrorKind.Stalled -> "TTS playback stalled$suffix"
        }
    }
}

object TtsWatchdogPlanning {
    private const val MIN_TIMEOUT_MS = 30_000L
    private const val MAX_TIMEOUT_MS = 5 * 60_000L
    private const val BASE_MS_PER_CHAR = 120f

    fun timeoutMs(
        textLength: Int,
        rate: Float,
    ): Long {
        val safeRate = rate.takeIf { it > 0f } ?: 1f
        val estimated = (textLength.coerceAtLeast(1) * (BASE_MS_PER_CHAR / safeRate)).roundToLong()
        return estimated.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
    }
}
