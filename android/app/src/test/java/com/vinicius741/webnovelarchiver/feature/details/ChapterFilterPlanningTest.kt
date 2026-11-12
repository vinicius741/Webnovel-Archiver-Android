package com.vinicius741.webnovelarchiver.feature.details

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.Story
import org.junit.Assert.assertEquals
import org.junit.Test

class ChapterFilterPlanningTest {
    private fun chapter(
        id: String,
        title: String = "Chapter $id",
        downloaded: Boolean = false,
        removedAt: Long? = null,
    ) = Chapter(id = id, title = title, downloaded = downloaded, removedFromSourceAt = removedAt)

    private val story =
        Story(
            id = "s",
            chapters =
                mutableListOf(
                    chapter("a", "Prologue", downloaded = true),
                    chapter("b", "The Gate", downloaded = true),
                    chapter("c", "Into the Gate"),
                    chapter("d", "Epilogue", downloaded = true, removedAt = 1L),
                    chapter("e", "Afterword 12"),
                ),
        )

    private fun ids(
        story: Story,
        query: String = "",
        filter: String = ALL_FILTER,
    ) = ChapterFilterPlanning.filter(story, query, filter).map { it.second.id }

    @Test
    fun allKeepsEveryChapterWithItsReadingPosition() {
        val shown = ChapterFilterPlanning.filter(story, "", ALL_FILTER)
        assertEquals(listOf(0, 1, 2, 3, 4), shown.map { it.first })
    }

    @Test
    fun downloadedAndNotDownloadedSplitTheChapters() {
        assertEquals(listOf("a", "b", "d"), ids(story, filter = DOWNLOADED_FILTER))
        assertEquals(listOf("c", "e"), ids(story, filter = NOT_DOWNLOADED_FILTER))
    }

    @Test
    fun queryMatchesTitleIgnoringCaseOrTheExactChapterNumber() {
        assertEquals(listOf("b", "c"), ids(story, query = "  gate "))
        // A number is matched exactly as a reading position, and also as text in a title.
        assertEquals(listOf("d"), ids(story, query = "4"))
        assertEquals(listOf("b", "e"), ids(story, query = "2"))
        assertEquals(listOf("e"), ids(story, query = "12"))
    }

    @Test
    fun queryAndFilterCombine() {
        assertEquals(listOf("c"), ids(story, query = "gate", filter = NOT_DOWNLOADED_FILTER))
    }

    @Test
    fun fromBookmarkStartsAtTheBookmarkAndShowsAllWithoutOne() {
        val bookmarked = story.copy(lastReadChapterId = "c")
        assertEquals(listOf("c", "d", "e"), ids(bookmarked, filter = FROM_BOOKMARK_FILTER))
        assertEquals(3, ChapterFilterPlanning.fromBookmarkCount(bookmarked))
        assertEquals(5, ids(story, filter = FROM_BOOKMARK_FILTER).size)
        assertEquals(0, ChapterFilterPlanning.fromBookmarkCount(story))
        assertEquals(0, ChapterFilterPlanning.fromBookmarkCount(story.copy(lastReadChapterId = "gone")))
    }

    @Test
    fun removedFromSourceShowsOnlyRemovedChaptersOrEverythingWhenNoneAre() {
        assertEquals(listOf("d"), ids(story, filter = REMOVED_FROM_SOURCE_FILTER))
        val none = story.copy(chapters = story.chapters.map { it.copy(removedFromSourceAt = null) }.toMutableList())
        assertEquals(5, ids(none, filter = REMOVED_FROM_SOURCE_FILTER).size)
    }
}
