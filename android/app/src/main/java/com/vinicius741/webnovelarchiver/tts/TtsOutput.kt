package com.vinicius741.webnovelarchiver.tts

/** The controls every kind of playback shares. */
internal interface TtsPlaybackControls {
    fun play()

    fun pause()

    fun setRate(rate: Float)

    fun setVolume(volume: Float) = Unit

    fun position(): TtsTimelinePoint
}

/** What a chapter's narration is played through: rendered audio, or the engine speaking live. */
internal interface TtsOutput : TtsPlaybackControls {
    fun load(request: TtsOutputLoad)

    fun seek(
        item: Int,
        offsetMs: Long,
    )

    /** Stops the current chapter and keeps the output reusable. */
    fun stop()

    /** Renders these sentences ahead of need, when this output is able to. */
    fun prepareAhead(texts: List<String>) = Unit

    fun release() = stop()

    /** Called on the engine's serialized state path. */
    interface Listener {
        fun onItemStarted(item: Int)

        fun onEnded()

        /** This output cannot work on this device; the caller should switch to another. */
        fun onUnavailable()

        fun onError(error: TtsPlaybackError)

        fun onRenderPlanFinished()
    }
}

internal data class TtsOutputLoad(
    val timeline: TtsTimeline,
    val item: Int,
    val offsetMs: Long,
    val rate: Float,
    val playWhenReady: Boolean,
    val voiceKey: String,
)
