package com.vinicius741.webnovelarchiver.domain.model

/** Active "now playing" session: exists while the player is loaded (playing or paused). */
data class TtsSession(
    var storyId: String = "",
    var storyTitle: String = "",
    var chapterId: String = "",
    var chapterTitle: String = "",
    var currentChunkIndex: Int = 0,
    var isPaused: Boolean = false,
    var wasPlaying: Boolean = false,
    var voiceIdentifier: String? = null,
    var rate: Float = 1.0f,
    var pitch: Float = 1.0f,
    var updatedAt: Long = System.currentTimeMillis(),
    var sessionVersion: Int = 1,
    /** Audio time already heard within the current chunk, so resume can land mid-sentence. */
    var chunkOffsetMs: Long = 0L,
    /** Opening text of the current chunk; keeps the position valid when chunk numbering shifts. */
    var anchorText: String? = null,
    /** 0 = saved before anchors existed, so [currentChunkIndex] uses the pre-merge sentence numbering. */
    var chunkingVersion: Int = 0,
    var history: List<TtsHistoryEntry> = emptyList(),
)

/** Last heard position per story; survives explicit stops so playback restarts where it left off. */
data class TtsStoryPosition(
    var storyId: String = "",
    var chapterId: String = "",
    var chapterTitle: String = "",
    var currentChunkIndex: Int = 0,
    var updatedAt: Long = System.currentTimeMillis(),
    var chunkOffsetMs: Long = 0L,
    var anchorText: String? = null,
    var chunkingVersion: Int = 0,
    /** The story finished here; "continue" means the next chapter once one exists. */
    var caughtUp: Boolean = false,
    var history: List<TtsHistoryEntry> = emptyList(),
)

/** A place the listener jumped away from, kept so an accidental skip can be undone. */
data class TtsHistoryEntry(
    var chapterId: String = "",
    var chapterTitle: String = "",
    var chunkIndex: Int = 0,
    var chunkOffsetMs: Long = 0L,
    var anchorText: String? = null,
    var chunkingVersion: Int = 0,
    var recordedAt: Long = 0L,
)
