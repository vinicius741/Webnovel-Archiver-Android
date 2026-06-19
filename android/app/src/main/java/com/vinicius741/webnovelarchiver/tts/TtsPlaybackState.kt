package com.vinicius741.webnovelarchiver.tts

import com.vinicius741.webnovelarchiver.domain.model.TtsSession

/**
 * A point-in-time description of TTS playback, used to drive the MediaSession playback state +
 * metadata (parity gap 1) and the in-reader floating transport (parity gap 4). Built from the
 * persisted [TtsSession] + the live chunk count, so the foreground service, the MediaSession, and
 * the reader transport all observe one consistent snapshot.
 */
data class TtsPlaybackSnapshot(
    val title: String,
    val storyId: String,
    val chapterId: String,
    /** Zero-based index of the chunk currently (or about to be) spoken. */
    val chunkIndex: Int,
    /** Total chunks in the current chapter; 0 when nothing is loaded. */
    val totalChunks: Int,
    val isPlaying: Boolean,
    val isPaused: Boolean,
)

object TtsPlaybackState {
    /**
     * Returns the chapter the visible reader should open when TTS moves to another chapter in the
     * same story. Chunk updates for the chapter already on screen, stopped playback, and snapshots
     * from a different story do not cause reader navigation.
     */
    fun readerChapterTransition(
        currentStoryId: String,
        currentChapterId: String,
        snapshot: TtsPlaybackSnapshot?,
    ): String? =
        snapshot
            ?.takeIf { it.storyId == currentStoryId && it.chapterId != currentChapterId }
            ?.chapterId

    /** Human-readable "Chunk X / Y" label used by the notification body + the reader transport. */
    fun chunkProgress(
        chunkIndex: Int,
        totalChunks: Int,
    ): String {
        if (totalChunks <= 0) return "Buffering"
        val shown = (chunkIndex + 1).coerceIn(1, totalChunks)
        return "Chunk $shown / $totalChunks"
    }

    /**
     * Builds a snapshot from the persisted [TtsSession] and the live chunk count. `totalChunks`
     * comes from the engine's in-memory chunk list (which matches the reader's `data-tts-group`
     * groups), while every other field is read from the persisted session so the snapshot survives
     * across the service / activity process boundary.
     *
     * Returns `null` when there is no eligible session, so callers can branch on "nothing playing".
     */
    fun snapshotForSession(
        session: TtsSession?,
        totalChunks: Int,
        isPlaying: Boolean,
    ): TtsPlaybackSnapshot? {
        if (session == null) return null
        if (session.storyId.isBlank() || session.chapterId.isBlank()) return null
        if (!TtsSessionPlanning.isResumeEligible(session)) return null
        return TtsPlaybackSnapshot(
            title = session.chapterTitle.ifBlank { "Reading" },
            storyId = session.storyId,
            chapterId = session.chapterId,
            chunkIndex = session.currentChunkIndex.coerceAtLeast(0),
            totalChunks = totalChunks.coerceAtLeast(0),
            // A persisted session reports "paused" whenever it is not actively playing.
            isPlaying = isPlaying,
            isPaused = !isPlaying,
        )
    }
}
