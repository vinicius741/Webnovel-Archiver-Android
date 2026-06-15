package com.vinicius741.webnovelarchiver.core

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

data class SelectedChapterForEpub(
    val chapter: Chapter,
    val originalChapterNumber: Int,
)

object EpubSelection {
    fun selectDownloadedChapters(story: Story, config: EpubConfig): List<SelectedChapterForEpub> {
        if (story.chapters.isEmpty()) return emptyList()

        var start = config.rangeStart.coerceIn(1, story.chapters.size)
        val end = config.rangeEnd.coerceIn(start, story.chapters.size)
        if (config.startAfterBookmark && story.lastReadChapterId != null) {
            val bookmarkIndex = story.chapters.indexOfFirst { it.id == story.lastReadChapterId }
            if (bookmarkIndex >= 0) {
                start = maxOf(start, bookmarkIndex + 2)
            }
        }

        return story.chapters
            .mapIndexed { index, chapter -> SelectedChapterForEpub(chapter, index + 1) }
            .filter { it.originalChapterNumber in start..end && it.chapter.downloaded }
    }

    fun displayNameForPath(path: String): String {
        val decoded = runCatching {
            URLDecoder.decode(path, StandardCharsets.UTF_8.name())
        }.getOrDefault(path)
        val filename = decoded
            .split('/', ':')
            .lastOrNull()
            ?.ifBlank { null }
            ?: "Unknown File"
        return filename.replace(Regex("\\.epub$", RegexOption.IGNORE_CASE), "").replace('_', ' ')
    }
}
