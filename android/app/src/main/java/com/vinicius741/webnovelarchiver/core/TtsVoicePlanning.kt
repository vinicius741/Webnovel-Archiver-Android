package com.vinicius741.webnovelarchiver.core

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

    private fun baseLanguage(languageTag: String): String = Locale.forLanguageTag(languageTag).language.lowercase(Locale.ROOT)
}
