package com.vinicius741.webnovelarchiver.tts

import com.vinicius741.webnovelarchiver.domain.model.TtsProfile
import com.vinicius741.webnovelarchiver.domain.model.TtsSettings

/** A speech engine (TTS app) installed on the device. */
internal data class TtsEngineInfo(
    val packageName: String,
    val label: String,
)

internal object TtsEnginePlanning {
    const val GOOGLE_TTS = "com.google.android.tts"

    /**
     * The engine package to speak with: the chosen one while it is installed, otherwise null, the
     * phone's preferred engine. Android falls back on its own when a named engine is missing, so
     * deciding here keeps the audio cache key naming the engine that really speaks.
     */
    fun resolve(
        requested: String?,
        installed: Collection<String>,
    ): String? = requested?.takeIf { it in installed }

    /**
     * What live speech (story descriptions) uses while the chosen engine cannot
     * speak on this phone: a built-in model whose files are not here, since chapters generate on the
     * user's computers. A saved profile that speaks with an Android engine lends its engine, voice and
     * pitch, Google's first; the listening speed and pauses stay the user's. Without one, the phone's
     * own engine speaks, as for any missing engine.
     */
    fun standIn(
        settings: TtsSettings,
        usable: Collection<String>,
        isBuiltIn: (String?) -> Boolean,
    ): TtsSettings {
        val profile =
            settings.profiles
                .filter { !isBuiltIn(it.enginePackage) && (it.enginePackage == null || it.enginePackage in usable) }
                .sortedWith(
                    compareByDescending<TtsProfile> { it.enginePackage == GOOGLE_TTS }
                        .thenByDescending { it.name.contains("google", ignoreCase = true) }
                        .thenByDescending { it.enginePackage != null },
                ).firstOrNull()
                ?: return settings.copy(enginePackage = null, voiceIdentifier = null)
        return settings.copy(enginePackage = profile.enginePackage, voiceIdentifier = profile.voiceIdentifier, pitch = profile.pitch)
    }

    fun sorted(engines: List<TtsEngineInfo>): List<TtsEngineInfo> =
        engines.distinctBy { it.packageName }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })

    /** What the engine setting shows: the engine's name, or why it is not the one in use. */
    fun label(
        selected: String?,
        engines: List<TtsEngineInfo>,
        phoneDefault: String?,
    ): String {
        if (selected == null) {
            val name = engines.firstOrNull { it.packageName == phoneDefault }?.label
            return if (name == null) "Phone default" else "Phone default ($name)"
        }
        return engines.firstOrNull { it.packageName == selected }?.label ?: "Not installed: $selected"
    }
}
