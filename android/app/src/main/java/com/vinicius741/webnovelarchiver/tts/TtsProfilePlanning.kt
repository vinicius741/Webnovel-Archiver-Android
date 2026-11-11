package com.vinicius741.webnovelarchiver.tts

import com.vinicius741.webnovelarchiver.domain.model.TtsProfile
import com.vinicius741.webnovelarchiver.domain.model.TtsSettings

/**
 * Saved voice setups. A profile holds what decides how narration sounds (engine, voice, pitch, rate
 * and pauses); playback behavior such as chapter announcements stays a single app-wide choice.
 */
internal object TtsProfilePlanning {
    const val MAX_NAME_LENGTH = 40
    private val whitespace = Regex("\\s+")

    /** A usable name, or null when nothing is left after trimming. */
    fun cleanName(raw: String): String? =
        raw
            .trim()
            .replace(whitespace, " ")
            .take(MAX_NAME_LENGTH)
            .trim()
            .ifBlank { null }

    fun capture(
        settings: TtsSettings,
        id: String,
        name: String,
    ): TtsProfile =
        TtsProfile(
            id = id,
            name = name,
            enginePackage = settings.enginePackage,
            voiceIdentifier = settings.voiceIdentifier,
            pitch = settings.pitch,
            rate = settings.rate,
            phrasePauseMs = settings.phrasePauseMs,
            sentencePauseMs = settings.sentencePauseMs,
            paragraphPauseMs = settings.paragraphPauseMs,
            scenePauseMs = settings.scenePauseMs,
        )

    /** [settings] sounding like [profile], which becomes the active one. */
    fun apply(
        settings: TtsSettings,
        profile: TtsProfile,
    ): TtsSettings =
        settings.copy(
            enginePackage = profile.enginePackage,
            voiceIdentifier = profile.voiceIdentifier,
            pitch = profile.pitch,
            rate = profile.rate,
            phrasePauseMs = profile.phrasePauseMs,
            sentencePauseMs = profile.sentencePauseMs,
            paragraphPauseMs = profile.paragraphPauseMs,
            scenePauseMs = profile.scenePauseMs,
            activeProfileId = profile.id,
        )

    fun matches(
        profile: TtsProfile,
        settings: TtsSettings,
    ): Boolean = capture(settings, profile.id, profile.name) == profile

    fun active(settings: TtsSettings): TtsProfile? = settings.profiles.firstOrNull { it.id == settings.activeProfileId }

    fun named(
        profiles: List<TtsProfile>,
        name: String,
    ): TtsProfile? = profiles.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /**
     * Saves the current settings under [name]. A profile already called that is overwritten in
     * place; otherwise a new one is added with [newId]. Either way it becomes the active profile.
     */
    fun save(
        settings: TtsSettings,
        name: String,
        newId: () -> String,
    ): TtsSettings {
        val existing = named(settings.profiles, name)
        val saved = capture(settings, existing?.id ?: newId(), name)
        val profiles =
            if (existing == null) {
                settings.profiles + saved
            } else {
                settings.profiles.map { if (it.id == existing.id) saved else it }
            }
        return settings.copy(profiles = profiles, activeProfileId = saved.id)
    }

    fun delete(
        settings: TtsSettings,
        id: String,
    ): TtsSettings =
        settings.copy(
            profiles = settings.profiles.filterNot { it.id == id },
            activeProfileId = settings.activeProfileId?.takeIf { it != id },
        )

    /** Whether going from [old] to [new] changes the sound itself, so rendered audio must be redone. */
    fun soundsDifferent(
        old: TtsSettings,
        new: TtsSettings,
    ): Boolean = old.enginePackage != new.enginePackage || old.voiceIdentifier != new.voiceIdentifier || old.pitch != new.pitch
}
