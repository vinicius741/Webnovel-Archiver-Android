package com.vinicius741.webnovelarchiver.tts

import com.vinicius741.webnovelarchiver.domain.model.TtsSettings
import com.vinicius741.webnovelarchiver.tts.kokoro.BuiltInModel

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
     * What live speech uses while the chosen engine cannot speak on this phone (a built-in model
     * whose files are not here, since chapters generate on the user's computers): the phone's own
     * engine and voice, as for any missing engine.
     */
    fun standIn(settings: TtsSettings): TtsSettings = settings.copy(enginePackage = null, voiceIdentifier = null)

    /** Whether going from [old] to [new] changes the sound itself, so rendered audio must be redone. */
    fun soundsDifferent(
        old: TtsSettings,
        new: TtsSettings,
    ): Boolean = old.enginePackage != new.enginePackage || old.voiceIdentifier != new.voiceIdentifier || old.pitch != new.pitch

    /** Only Google's engine has a pitch control that does something. */
    fun hasPitch(enginePackage: String?): Boolean = enginePackage == GOOGLE_TTS

    /**
     * Settings that chose a retired built-in model ([BuiltInModel.retired]) move to its replacement,
     * keeping the voice when the replacement has one with the same code, else its default voice.
     * Audio already generated with the retired model keeps playing: playback never needs the engine.
     */
    fun withoutRetiredEngine(settings: TtsSettings): TtsSettings {
        val model = BuiltInModel.forEngine(settings.enginePackage)?.takeIf { it.retired } ?: return settings
        val replacement = model.replacement ?: return settings.copy(enginePackage = null, voiceIdentifier = null)
        val code = BuiltInModel.forIdentifier(settings.voiceIdentifier)?.code
        val voice = replacement.voices.firstOrNull { it.code == code }
        return settings.copy(enginePackage = replacement.engineId, voiceIdentifier = voice?.let { replacement.idPrefix + it.code })
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
