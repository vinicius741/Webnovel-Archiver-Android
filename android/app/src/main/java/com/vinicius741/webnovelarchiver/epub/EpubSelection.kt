package com.vinicius741.webnovelarchiver.epub

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.EpubConfig
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.ui.size
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

data class SelectedChapterForEpub(
    val chapter: Chapter,
    val originalChapterNumber: Int,
)

/**
 * How many chapters fall inside the effective EPUB range vs. how many of those are downloaded.
 * Used to warn before generating an EPUB from an incomplete download ([missing] > 0). Only the
 * downloadable subset is ever written to the EPUB (see [EpubSelection.selectDownloadedChapters]).
 */
data class EpubRangeCoverage(
    val inRange: Int,
    val downloaded: Int,
) {
    val missing: Int get() = (inRange - downloaded).coerceAtLeast(0)
}

object EpubSelection {
    fun selectDownloadedChapters(
        story: Story,
        config: EpubConfig,
    ): List<SelectedChapterForEpub> {
        if (story.chapters.isEmpty()) return emptyList()
        val range = effectiveRange(story, config)

        return story.chapters
            .mapIndexed { index, chapter -> SelectedChapterForEpub(chapter, index + 1) }
            .filter { it.originalChapterNumber in range && it.chapter.downloaded }
    }

    /**
     * Counts chapters in the effective range and how many of them are downloaded. Returns zeros when
     * there are no chapters. The range math mirrors [selectDownloadedChapters] via [effectiveRange]
     * so the two never drift.
     */
    fun rangeCoverage(
        story: Story,
        config: EpubConfig,
    ): EpubRangeCoverage {
        if (story.chapters.isEmpty()) return EpubRangeCoverage(inRange = 0, downloaded = 0)
        val range = effectiveRange(story, config)
        val inRangeIndices = (range.first - 1) until range.last
        val inRange = inRangeIndices.count { it in story.chapters.indices }
        val downloaded = inRangeIndices.count { it in story.chapters.indices && story.chapters[it].downloaded }
        return EpubRangeCoverage(inRange = inRange, downloaded = downloaded)
    }

    /**
     * The 1-based inclusive chapter range after clamping to the chapter list and applying the
     * optional "start after bookmark" bump. Shared by [selectDownloadedChapters] and [rangeCoverage]
     * so coverage reflects exactly what would be exported.
     */
    private fun effectiveRange(
        story: Story,
        config: EpubConfig,
    ): IntRange {
        var start = config.rangeStart.coerceIn(1, story.chapters.size)
        val end = config.rangeEnd.coerceIn(start, story.chapters.size)
        if (config.startAfterBookmark && story.lastReadChapterId != null) {
            val bookmarkIndex = story.chapters.indexOfFirst { it.id == story.lastReadChapterId }
            if (bookmarkIndex >= 0) {
                start = maxOf(start, bookmarkIndex + 2)
            }
        }
        return start..end
    }

    fun displayNameForPath(path: String): String {
        val decoded =
            runCatching {
                URLDecoder.decode(path, StandardCharsets.UTF_8.name())
            }.getOrDefault(path)
        val filename =
            decoded
                .split('/', ':')
                .lastOrNull()
                ?.ifBlank { null }
                ?: "Unknown File"
        return filename.replace(Regex("\\.epub$", RegexOption.IGNORE_CASE), "").replace('_', ' ')
    }
}
