package com.vinicius741.webnovelarchiver.tts

import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.model.TtsSession
import com.vinicius741.webnovelarchiver.domain.model.TtsSettings

object TtsSessionPlanning {
    data class ReaderResumeTarget(
        val storyId: String,
        val chapterId: String,
    )

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

    fun restoredChunkSize(settings: TtsSettings): Int = settings.chunkSize.coerceAtLeast(100)

    fun nextChunkRequestIndex(
        currentPostIncrementIndex: Int,
        chunkCount: Int,
    ): Int {
        if (chunkCount <= 0) return 0
        return currentPostIncrementIndex.coerceIn(0, chunkCount - 1)
    }

    fun previousChunkRequestIndex(
        currentPostIncrementIndex: Int,
        chunkCount: Int,
    ): Int {
        if (chunkCount <= 0) return 0
        return (currentPostIncrementIndex - 2).coerceIn(0, chunkCount - 1)
    }

    fun nextChapterIndex(
        story: Story,
        currentChapterId: String,
    ): Int? {
        val current = story.chapters.indexOfFirst { it.id == currentChapterId }
        if (current < 0 || current >= story.chapters.lastIndex) return null
        return current + 1
    }
}
