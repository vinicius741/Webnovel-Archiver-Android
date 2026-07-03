package com.vinicius741.webnovelarchiver.tts

import android.speech.tts.Voice
import java.util.Locale

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

    private fun baseLanguage(languageTag: String): String = Locale.forLanguageTag(languageTag).language.lowercase(Locale.ROOT)
}
