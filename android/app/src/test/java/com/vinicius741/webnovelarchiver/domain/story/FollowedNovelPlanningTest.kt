package com.vinicius741.webnovelarchiver.domain.story

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.PublicationStatus
import com.vinicius741.webnovelarchiver.domain.model.Story
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowedNovelPlanningTest {
    @Test
    fun chaptersBehindEndMeasuresFromBookmarkToLastChapter() {
        assertEquals(0, FollowedNovelPlanning.chaptersBehindEnd(story(bookmarkIndex = 9, count = 10)))
        assertEquals(5, FollowedNovelPlanning.chaptersBehindEnd(story(bookmarkIndex = 4, count = 10)))
        assertNull(FollowedNovelPlanning.chaptersBehindEnd(story(bookmarkIndex = null, count = 10)))
        assertNull(FollowedNovelPlanning.chaptersBehindEnd(story(bookmarkId = "missing", count = 10)))
        assertNull(FollowedNovelPlanning.chaptersBehindEnd(story(bookmarkIndex = null)))
    }

    @Test
    fun isFollowedAcceptsBookmarkWithinThresholdAndRejectsBeyond() {
        assertTrue(FollowedNovelPlanning.isFollowed(story(bookmarkIndex = 9, count = 10), thresholdChapters = 5))
        assertTrue(FollowedNovelPlanning.isFollowed(story(bookmarkIndex = 4, count = 10), thresholdChapters = 5))
        assertFalse(FollowedNovelPlanning.isFollowed(story(bookmarkIndex = 3, count = 10), thresholdChapters = 5))
        assertFalse(FollowedNovelPlanning.isFollowed(story(bookmarkIndex = null, count = 10), thresholdChapters = 5))
    }

    @Test
    fun isFollowedSkipsCompletedButKeepsResumableStatuses() {
        assertFalse(FollowedNovelPlanning.isFollowed(story(bookmarkIndex = 9, count = 10, status = PublicationStatus.completed), 5))
        assertTrue(FollowedNovelPlanning.isFollowed(story(bookmarkIndex = 9, count = 10, status = PublicationStatus.hiatus), 5))
        assertTrue(FollowedNovelPlanning.isFollowed(story(bookmarkIndex = 9, count = 10, status = PublicationStatus.outdated), 5))
        assertTrue(FollowedNovelPlanning.isFollowed(story(bookmarkIndex = 9, count = 10, status = PublicationStatus.unknown), 5))
    }

    @Test
    fun isFollowedSkipsArchivedSnapshots() {
        assertFalse(FollowedNovelPlanning.isFollowed(story(bookmarkIndex = 9, count = 10, archived = true), 5))
    }

    @Test
    fun followedStoriesPreserveLibraryOrder() {
        val stories =
            listOf(
                story(id = "far", bookmarkIndex = 0, count = 10),
                story(id = "near", bookmarkIndex = 9, count = 10),
                story(id = "edge", bookmarkIndex = 5, count = 10),
            )

        val followed = FollowedNovelPlanning.followedStories(stories, thresholdChapters = 5)

        assertEquals(listOf("near", "edge"), followed.map { it.id })
    }

    @Test
    fun distanceUsesTheChapterListNotTheDenormalizedTotal() {
        val staleTotal =
            story(bookmarkIndex = 9, count = 10).copy(totalChapters = 999)

        assertEquals(0, FollowedNovelPlanning.chaptersBehindEnd(staleTotal))
        assertTrue(FollowedNovelPlanning.isFollowed(staleTotal, 5))
    }

    @Test
    fun reviewEntriesSortFollowedFirstByDistanceWithBlockedLast() {
        val entries =
            FollowedNovelPlanning.reviewEntries(
                listOf(
                    story(id = "no-bookmark", bookmarkIndex = null, count = 10, title = "A No Bookmark"),
                    story(id = "mid", bookmarkIndex = 6, count = 10, title = "Mid"),
                    story(id = "caught-up", bookmarkIndex = 9, count = 10, title = "Caught Up"),
                    story(id = "completed", bookmarkIndex = 9, count = 10, title = "B Completed", status = PublicationStatus.completed),
                    story(id = "edge", bookmarkIndex = 4, count = 10, title = "Edge"),
                ),
                thresholdChapters = 5,
            )

        assertEquals(
            listOf("Caught Up", "Mid", "Edge", "B Completed", "A No Bookmark"),
            entries.map { it.story.title },
        )
    }

    @Test
    fun reviewEntryReportsBlockReasons() {
        assertNull(FollowedNovelPlanning.reviewEntry(story(bookmarkIndex = 9, count = 10), 5).blockReason)
        assertEquals(
            FollowBlockReason.TOO_FAR,
            FollowedNovelPlanning.reviewEntry(story(bookmarkIndex = 2, count = 10), 5).blockReason,
        )
        assertEquals(
            FollowBlockReason.NO_BOOKMARK,
            FollowedNovelPlanning.reviewEntry(story(bookmarkIndex = null, count = 10), 5).blockReason,
        )
        assertEquals(
            FollowBlockReason.COMPLETED,
            FollowedNovelPlanning.reviewEntry(story(bookmarkIndex = 9, count = 10, status = PublicationStatus.completed), 5).blockReason,
        )
    }

    private fun story(
        id: String = "s",
        title: String = id,
        bookmarkIndex: Int? = null,
        count: Int = 10,
        bookmarkId: String? = null,
        status: PublicationStatus = PublicationStatus.unknown,
        archived: Boolean = false,
    ): Story {
        val chapters = (1..count).map { Chapter(id = "c$it") }.toMutableList()
        return Story(
            id = id,
            title = title,
            sourceUrl = "https://example.com/$id",
            chapters = chapters,
            lastReadChapterId = bookmarkId ?: bookmarkIndex?.let { chapters[it].id },
            publicationStatus = status,
            isArchived = archived.takeIf { it },
        )
    }
}
