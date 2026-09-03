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
)

/** Last heard position per story; survives explicit stops so playback restarts where it left off. */
data class TtsStoryPosition(
    var storyId: String = "",
    var chapterId: String = "",
    var chapterTitle: String = "",
    var currentChunkIndex: Int = 0,
    var updatedAt: Long = System.currentTimeMillis(),
)
