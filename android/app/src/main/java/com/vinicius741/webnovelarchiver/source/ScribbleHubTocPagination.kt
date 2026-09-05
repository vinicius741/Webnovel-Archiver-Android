package com.vinicius741.webnovelarchiver.source

import com.vinicius741.webnovelarchiver.domain.model.ChapterInfo
import com.vinicius741.webnovelarchiver.source.network.SourceAccessBlockedException
import com.vinicius741.webnovelarchiver.source.network.SourceChapterListIncompleteException

/*
 * Scribble Hub TOC pagination rules, split out of [ScribbleHubProvider] to keep that object inside
 * its file-size budget (R03).
 */

private const val MAX_TOC_PAGE_SIZE = 50
private const val TOC_PAGE_LIMIT = 500

private fun chapterCountLabel(count: Int): String = "$count ${if (count == 1) "chapter" else "chapters"}"

internal fun incompleteTocException(
    page: Int,
    cause: Throwable,
): SourceChapterListIncompleteException =
    SourceChapterListIncompleteException("Scribble Hub chapter page $page was blocked by the source", cause)

/**
 * Drives TOC pagination to a provably complete list. Throws [SourceChapterListIncompleteException]
 * when a page is blocked or the page limit is reached without an observed end, so callers can
 * never mistake a partial list for the full one (R03).
 */
internal suspend fun paginateTocPages(
    start: List<ChapterInfo>,
    fetchPage: suspend (page: Int) -> List<ChapterInfo>,
    progress: (String) -> Unit,
): List<ChapterInfo> {
    val chapters = start.toMutableList()
    val seen = start.map { it.url }.toMutableSet()
    var observedEnd = false
    for (page in 2..TOC_PAGE_LIMIT) {
        progress("Fetching chapter page $page · ${chapterCountLabel(chapters.size)} found...")
        val pageChapters =
            try {
                fetchPage(page)
            } catch (error: SourceAccessBlockedException) {
                throw incompleteTocException(page, error)
            }
        val newOnes = pageChapters.filter { seen.add(it.url) }
        chapters.addAll(newOnes)
        // A short page or an all-duplicate page is the site's own "no more chapters" signal.
        if (pageChapters.size < MAX_TOC_PAGE_SIZE || newOnes.isEmpty()) {
            observedEnd = true
            break
        }
    }
    if (!observedEnd) {
        throw SourceChapterListIncompleteException(
            "Scribble Hub chapter pagination reached its page limit ($TOC_PAGE_LIMIT) before the list ended",
        )
    }
    return chapters
}
