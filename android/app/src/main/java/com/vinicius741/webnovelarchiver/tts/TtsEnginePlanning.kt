package com.vinicius741.webnovelarchiver.tts

/** A speech engine (TTS app) installed on the device. */
internal data class TtsEngineInfo(
    val packageName: String,
    val label: String,
)

internal object TtsEnginePlanning {
    /**
     * The engine package to speak with: the chosen one while it is installed, otherwise null, the
     * phone's preferred engine. Android falls back on its own when a named engine is missing, so
     * deciding here keeps the audio cache key naming the engine that really speaks.
     */
    fun resolve(
        requested: String?,
        installed: Collection<String>,
    ): String? = requested?.takeIf { it in installed }

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
