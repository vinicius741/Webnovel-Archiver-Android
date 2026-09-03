package com.vinicius741.webnovelarchiver.tts

import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.model.TtsSession
import com.vinicius741.webnovelarchiver.domain.model.TtsStoryPosition

object TtsSessionPlanning {
    data class ReaderResumeTarget(
        val storyId: String,
        val chapterId: String,
    )

    data class StartPosition(
        val chapterId: String,
        val chunkIndex: Int,
    )

    /**
     * Where "Read aloud" should start for a story: its saved per-story position when that chapter is
     * still playable (downloaded, or legacy inline content), else the chapter the user is looking at
     * from the beginning. Description sessions never consult positions (they have none of their own).
     */
    fun resolveStartPosition(
        story: Story,
        requestedChapterId: String,
        position: TtsStoryPosition?,
    ): StartPosition {
        if (TtsDescriptionPlanning.isDescriptionSession(requestedChapterId)) return StartPosition(requestedChapterId, 0)
        if (position != null && position.storyId == story.id) {
            val chapter = story.chapters.firstOrNull { it.id == position.chapterId }
            if (chapter != null && (chapter.downloaded || !chapter.content.isNullOrBlank())) {
                return StartPosition(chapter.id, position.currentChunkIndex.coerceAtLeast(0))
            }
        }
        return StartPosition(requestedChapterId, 0)
    }

    /** Per-story position mirrored from a live session; description sessions persist none. */
    fun storyPosition(session: TtsSession): TtsStoryPosition? =
        if (TtsDescriptionPlanning.isDescriptionSession(session.chapterId) || session.storyId.isBlank()) {
            null
        } else {
            TtsStoryPosition(
                storyId = session.storyId,
                chapterId = session.chapterId,
                chapterTitle = session.chapterTitle,
                currentChunkIndex = session.currentChunkIndex.coerceAtLeast(0),
                updatedAt = session.updatedAt,
            )
        }

    fun isResumeEligible(session: TtsSession?): Boolean {
        if (session == null) return false
        if (session.storyId.isBlank() || session.chapterId.isBlank()) return false
        return session.wasPlaying || session.isPaused
    }

    fun readerResumeTarget(
        session: TtsSession?,
        storyProvider: (String) -> Story?,
    ): ReaderResumeTarget? {
        if (!isResumeEligible(session)) return null
        val activeSession = session ?: return null
        val story = storyProvider(activeSession.storyId) ?: return null
        val chapterExists = story.chapters.any { it.id == activeSession.chapterId }
        if (!chapterExists) return null
        return ReaderResumeTarget(activeSession.storyId, activeSession.chapterId)
    }

    fun boundedChunkIndex(
        session: TtsSession,
        chunkCount: Int,
    ): Int {
        if (chunkCount <= 0) return 0
        return session.currentChunkIndex.coerceIn(0, chunkCount - 1)
    }

    fun nextChunkIndex(
        currentChunkIndex: Int,
        chunkCount: Int,
    ): Int {
        if (chunkCount <= 1) return 0
        return currentChunkIndex.coerceIn(0, chunkCount - 2) + 1
    }

    fun previousChunkIndex(
        currentChunkIndex: Int,
        chunkCount: Int,
    ): Int {
        if (chunkCount <= 1) return 0
        return currentChunkIndex.coerceIn(1, chunkCount - 1) - 1
    }

    fun nextChapterIndex(
        story: Story,
        currentChapterId: String,
    ): Int? {
        val current = story.chapters.indexOfFirst { it.id == currentChapterId }
        if (current < 0 || current >= story.chapters.lastIndex) return null
        return current + 1
    }

    /** Chapter index for a manual skip ([delta] is -1 or +1); null when there is none in that direction. */
    fun chapterIndexAtDelta(
        story: Story,
        currentChapterId: String,
        delta: Int,
    ): Int? {
        val current = story.chapters.indexOfFirst { it.id == currentChapterId }
        if (current < 0) return null
        val target = current + delta
        if (target !in story.chapters.indices) return null
        return target
    }
}
