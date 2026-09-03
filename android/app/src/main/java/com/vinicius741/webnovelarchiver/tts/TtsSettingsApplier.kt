package com.vinicius741.webnovelarchiver.tts

import android.speech.tts.TextToSpeech
import com.vinicius741.webnovelarchiver.domain.model.TtsSettings
import timber.log.Timber
import java.util.Locale

internal object TtsSettingsApplier {
    fun apply(
        engine: TextToSpeech,
        settings: TtsSettings,
        onError: (TtsPlaybackError) -> Unit,
    ): Boolean {
        when (val result = TtsVoicePlanning.resolveVoice(engine.voices, settings)) {
            is VoiceSelectionResult.VoiceResolved -> {
                if (engine.setVoice(result.voice) == TextToSpeech.ERROR) {
                    onError(TtsPlaybackError(kind = TtsPlaybackErrorKind.VoiceRejected, detail = result.voice.name))
                    return false
                }
            }
            is VoiceSelectionResult.VoiceMissing -> {
                onError(TtsPlaybackError(kind = TtsPlaybackErrorKind.VoiceUnavailable, detail = result.identifier))
                return false
            }
            VoiceSelectionResult.UseDefaultLanguage -> {
                when (engine.setLanguage(Locale.getDefault())) {
                    TextToSpeech.LANG_MISSING_DATA -> {
                        onError(TtsPlaybackError(TtsPlaybackErrorKind.LanguageMissingData))
                        return false
                    }
                    TextToSpeech.LANG_NOT_SUPPORTED -> {
                        onError(TtsPlaybackError(TtsPlaybackErrorKind.LanguageNotSupported))
                        return false
                    }
                }
            }
        }
        if (engine.setPitch(settings.pitch) == TextToSpeech.ERROR) {
            Timber.w("TTS setPitch rejected value %s", settings.pitch)
        }
        if (engine.setSpeechRate(settings.rate) == TextToSpeech.ERROR) {
            Timber.w("TTS setSpeechRate rejected value %s", settings.rate)
        }
        return true
    }
}
