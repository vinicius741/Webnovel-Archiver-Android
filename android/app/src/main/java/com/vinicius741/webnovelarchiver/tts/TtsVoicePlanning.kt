package com.vinicius741.webnovelarchiver.tts

import android.speech.tts.Voice
import com.vinicius741.webnovelarchiver.domain.model.TtsSettings
import java.util.Locale

/**
 * Presentation + selection data for the TTS voice picker. `VoiceInfo` is the snapshot surfaced to
 * the settings UI; `toVoiceInfo` / `resolveVoice` hold the pure sort/filter/map and voice-selection
 * decision tree so [TtsEngine] can stay focused on playback state and side-effects.
 */
data class VoiceInfo(
    val identifier: String,
    val name: String,
    val language: String,
    val quality: Int,
    val latency: Int,
)

/**
 * Pure decision result of [TtsVoicePlanning.resolveVoice]. The engine switches on this to perform
 * the actual `engine.setVoice(...)` / `engine.setLanguage(...)` side-effects and to route errors
 * through `handlePlaybackErrorLocked`, preserving the exact branching/order from the original
 * inline implementation (including the LANG_MISSING_DATA / LANG_NOT_SUPPORTED cases).
 */
sealed interface VoiceSelectionResult {
    /** Voice matched `settings.voiceIdentifier`; the engine should call `setVoice(voice)`. */
    data class VoiceResolved(
        val voice: Voice,
    ) : VoiceSelectionResult

    /** `settings.voiceIdentifier` was set but no matching voice exists on the engine. */
    data class VoiceMissing(
        val identifier: String,
    ) : VoiceSelectionResult

    /**
     * No voice preference — fall back to the device default language. The engine should call
     * `setLanguage(Locale.getDefault())` and inspect the returned status code itself, because the
     * status is a side-effect of that call and cannot be known up front.
     */
    object UseDefaultLanguage : VoiceSelectionResult
}

object TtsVoicePlanning {
    fun languageCodes(voices: List<VoiceInfo>): List<String> =
        voices
            .map { baseLanguage(it.language) }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()

    fun filter(
        voices: List<VoiceInfo>,
        query: String,
        languageCode: String?,
        displayLocale: Locale = Locale.getDefault(),
    ): List<VoiceInfo> {
        val normalizedQuery = query.trim().lowercase(displayLocale)
        return voices.filter { voice ->
            val matchesLanguage = languageCode == null || baseLanguage(voice.language) == languageCode
            val searchable =
                listOf(
                    voice.name,
                    voice.identifier,
                    voice.language,
                    languageLabel(voice.language, displayLocale),
                ).joinToString(" ").lowercase(displayLocale)
            matchesLanguage && (normalizedQuery.isBlank() || normalizedQuery in searchable)
        }
    }

    fun languageLabel(
        languageTag: String,
        displayLocale: Locale = Locale.getDefault(),
    ): String = Locale.forLanguageTag(languageTag).getDisplayName(displayLocale).ifBlank { languageTag }

    fun languageFilterLabel(
        languageCode: String,
        displayLocale: Locale = Locale.getDefault(),
    ): String = Locale.forLanguageTag(languageCode).getDisplayLanguage(displayLocale).ifBlank { languageCode.uppercase(displayLocale) }

    fun voiceMetadataLabel(voice: VoiceInfo): String =
        listOf(
            qualityLabel(voice.quality),
            latencyLabel(voice.latency),
        ).joinToString(" · ")

    fun qualityLabel(quality: Int): String =
        when (quality) {
            Voice.QUALITY_VERY_HIGH -> "Very high quality"
            Voice.QUALITY_HIGH -> "High quality"
            Voice.QUALITY_NORMAL -> "Normal quality"
            Voice.QUALITY_LOW -> "Low quality"
            Voice.QUALITY_VERY_LOW -> "Very low quality"
            else -> "Quality $quality"
        }

    fun latencyLabel(latency: Int): String =
        when (latency) {
            Voice.LATENCY_VERY_LOW -> "Very low latency"
            Voice.LATENCY_LOW -> "Low latency"
            Voice.LATENCY_NORMAL -> "Normal latency"
            Voice.LATENCY_HIGH -> "High latency"
            Voice.LATENCY_VERY_HIGH -> "Very high latency"
            else -> "Latency $latency"
        }

    /**
     * Sorts and maps the engine's raw [Voice] set into the [VoiceInfo] list surfaced to the voice
     * picker. Drops network-required voices, orders by language tag then voice name so the picker
     * groups voices by language. Extracted from `TtsEngine.availableVoices()` so the engine keeps
     * only the lazy `TextToSpeech` construction side-effect.
     */
    fun toVoiceInfo(voices: Set<Voice>?): List<VoiceInfo> =
        voices
            ?.filter { !it.isNetworkConnectionRequired }
            ?.sortedWith(compareBy<Voice> { it.locale.toLanguageTag() }.thenBy { it.name })
            ?.map {
                VoiceInfo(
                    identifier = it.name,
                    name = it.name,
                    language = it.locale.toLanguageTag(),
                    quality = it.quality,
                    latency = it.latency,
                )
            }
            ?: emptyList()

    /**
     * Pure voice-selection decision for `TtsEngine.applySettingsLocked`. Given the engine's voices
     * and the user's [TtsSettings], returns which branch the engine should act on:
     *
     * - [VoiceSelectionResult.VoiceResolved] → call `engine.setVoice(voice)`.
     * - [VoiceSelectionResult.VoiceMissing] → the saved voice identifier no longer exists on this
     *   device; route a `VoiceUnavailable` playback error.
     * - [VoiceSelectionResult.UseDefaultLanguage] → no voice preference; call
     *   `engine.setLanguage(Locale.getDefault())` and check the returned status.
     *
     * The engine owns every side-effect; this function only decides which path applies.
     */
    fun resolveVoice(
        voices: Set<Voice>?,
        settings: TtsSettings,
    ): VoiceSelectionResult {
        val selectedVoice = settings.voiceIdentifier?.let { id -> voices?.firstOrNull { it.name == id } }
        return when {
            selectedVoice != null -> VoiceSelectionResult.VoiceResolved(selectedVoice)
            settings.voiceIdentifier != null -> VoiceSelectionResult.VoiceMissing(settings.voiceIdentifier)
            else -> VoiceSelectionResult.UseDefaultLanguage
        }
    }

    private fun baseLanguage(languageTag: String): String = Locale.forLanguageTag(languageTag).language.lowercase(Locale.ROOT)
}
