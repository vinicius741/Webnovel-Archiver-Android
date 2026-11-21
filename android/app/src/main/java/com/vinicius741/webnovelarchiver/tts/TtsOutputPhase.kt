package com.vinicius741.webnovelarchiver.tts

/**
 * What [TtsEngine]'s output is doing for the loaded chapter. This replaced two independent flags
 * (`playbackActive`, `pendingSpeakOnInit`) whose fourth combination meant nothing.
 *
 * The listener's own pause is separate and persisted (`TtsSession.isPaused`): moves, restores and
 * restarts keep it, and only an explicit play or a brief-interruption recovery may clear it.
 */
internal enum class TtsOutputPhase {
    /** Nothing should sound: paused, stopped, errored, or between chapters. */
    Silent,

    /** The output has been told to play. */
    Running,
    ;

    /** Playback was requested. */
    val wantsAudio: Boolean get() = this != Silent
}
