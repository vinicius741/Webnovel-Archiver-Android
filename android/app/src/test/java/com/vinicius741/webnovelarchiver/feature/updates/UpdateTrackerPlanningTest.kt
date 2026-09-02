package com.vinicius741.webnovelarchiver.feature.updates

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.PublicationStatus
import com.vinicius741.webnovelarchiver.domain.model.SourceAvailability
import com.vinicius741.webnovelarchiver.domain.model.SourceSyncState
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.story.FollowedNovelPlanning
import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateTrackerPlanningTest {
    @Test
    fun unavailableSummaryUsesCorrectPlural() {
        assertEquals(
            "1 unavailable novel will be skipped. Check manually from its details screen.",
            UpdateTrackerPlanning.unavailableSummary(1),
        )
        assertEquals(
            "2 unavailable novels will be skipped. Check manually from its details screen.",
            UpdateTrackerPlanning.unavailableSummary(2),
        )
    }

    @Test
    fun filterStoriesMatchesTitleOrAuthorWithoutChangingOrder() {
        val stories =
            listOf(
                story("b", title = "Second", author = "Matching Author"),
                story("a", title = "Matching Title", author = "Someone"),
                story("c", title = "Third", author = "Else"),
            )

        val filtered = UpdateTrackerPlanning.filterStories(stories, " matching ")

        assertEquals(listOf("b", "a"), filtered.map { it.id })
    }

    @Test
    fun followableStoriesExcludeArchivedSnapshots() {
        val stories =
            listOf(
                story("live", title = "Live"),
                story("arch", title = "Live", archived = true),
            )

        assertEquals(listOf("live"), UpdateTrackerPlanning.followableStories(stories).map { it.id })
    }

    @Test
    fun syncableFollowedStoriesDeriveFromBookmarkDistanceAndSkipUnavailable() {
        val stories =
            listOf(
                story("caught-up", chapters = chapters(10), bookmarkIndex = 9),
                story("edge", chapters = chapters(10), bookmarkIndex = 4),
                story("behind", chapters = chapters(10), bookmarkIndex = 2),
                story("no-bookmark", chapters = chapters(10)),
                story("blocked", chapters = chapters(10), bookmarkIndex = 9, unavailable = true),
            )

        val syncable = UpdateTrackerPlanning.syncableFollowedStories(stories, thresholdChapters = 5)

        assertEquals(listOf("caught-up", "edge"), syncable.map { it.id })
    }

    @Test
    fun reviewLabelsDescribeCountsAndThreshold() {
        assertEquals("3 of 10 following", UpdateTrackerPlanning.reviewHeaderLabel(3, 10))
        assertEquals("Novels (5)", UpdateTrackerPlanning.reviewNovelsLabel(5, 5))
        assertEquals("Novels (2 of 5)", UpdateTrackerPlanning.reviewNovelsLabel(2, 5))
        assertEquals("No matches" to "Try a different search.", UpdateTrackerPlanning.reviewEmptyCopy())
        assertEquals("Within 5 chapters of the end", UpdateTrackerPlanning.thresholdLabel(5))
        assertEquals("Within 1 chapter of the end", UpdateTrackerPlanning.thresholdLabel(1))
    }

    @Test
    fun reviewBadgeAndDistanceLabelsCoverEveryBlockReason() {
        val followed = FollowedNovelPlanning.reviewEntry(story("a", chapters = chapters(10), bookmarkIndex = 9), 5)
        val tooFar = FollowedNovelPlanning.reviewEntry(story("b", chapters = chapters(10), bookmarkIndex = 2), 5)
        val noBookmark = FollowedNovelPlanning.reviewEntry(story("c", chapters = chapters(10)), 5)
        val completed =
            FollowedNovelPlanning.reviewEntry(
                story("d", chapters = chapters(10), bookmarkIndex = 9, publicationStatus = PublicationStatus.completed),
                5,
            )

        assertEquals("Following", UpdateTrackerPlanning.reviewStatusBadgeLabel(followed))
        assertEquals("0 chapters behind the end", UpdateTrackerPlanning.reviewDistanceLabel(followed))
        assertEquals("Not following", UpdateTrackerPlanning.reviewStatusBadgeLabel(tooFar))
        assertEquals("7 chapters behind the end", UpdateTrackerPlanning.reviewDistanceLabel(tooFar))
        assertEquals("Not following", UpdateTrackerPlanning.reviewStatusBadgeLabel(noBookmark))
        assertEquals("No bookmark", UpdateTrackerPlanning.reviewDistanceLabel(noBookmark))
        assertEquals("Not following", UpdateTrackerPlanning.reviewStatusBadgeLabel(completed))
        assertEquals("Completed", UpdateTrackerPlanning.reviewDistanceLabel(completed))
    }

    @Test
    fun updatedChaptersReturnPendingChaptersInStoryOrder() {
        val story =
            story(
                "story",
                chapters =
                    mutableListOf(
                        Chapter(id = "c1", title = "One"),
                        Chapter(id = "c2", title = "Two"),
                        Chapter(id = "c3", title = "Three"),
                    ),
                pending = mutableListOf("c3", "missing", "c1"),
            )

        val updates = UpdateTrackerPlanning.updatedChapters(story)

        assertEquals(listOf(0, 2), updates.map { it.index })
        assertEquals(listOf("One", "Three"), updates.map { it.chapter.title })
    }

    @Test
    fun updatedChaptersCanUseSyncResultIdsAfterPendingIdsAreCleared() {
        val story =
            story(
                "story",
                chapters =
                    mutableListOf(
                        Chapter(id = "c1", title = "One", downloaded = true),
                        Chapter(id = "c2", title = "Two", downloaded = true),
                    ),
                pending = null,
            )

        val updates = UpdateTrackerPlanning.updatedChapters(story, chapterIds = listOf("c2"))

        assertEquals(listOf(1), updates.map { it.index })
        assertEquals(listOf("Two"), updates.map { it.chapter.title })
    }

    @Test
    fun updatedCountsUseSyncResultIdsWhenAvailable() {
        val stories =
            listOf(
                story("a", chapters = mutableListOf(Chapter(id = "a1"))),
                story("b", chapters = mutableListOf(Chapter(id = "b1")), pending = mutableListOf("b1")),
            )

        val count = UpdateTrackerPlanning.updatedChapterCount(stories, mapOf("a" to listOf("a1")))

        assertEquals(2, count)
    }

    @Test
    fun syncBatchesCapConcurrentStoryGroups() {
        val stories = listOf(story("a"), story("b"), story("c"), story("d"), story("e"))

        val batches = UpdateTrackerPlanning.syncBatches(stories, maxConcurrent = 2)

        assertEquals(
            listOf(listOf("a", "b"), listOf("c", "d"), listOf("e")),
            batches.map { batch -> batch.map { it.id } },
        )
    }

    @Test
    fun syncBatchesTreatInvalidConcurrencyAsSingleWorker() {
        val stories = listOf(story("a"), story("b"))

        val batches = UpdateTrackerPlanning.syncBatches(stories, maxConcurrent = 0)

        assertEquals(
            listOf(listOf("a"), listOf("b")),
            batches.map { batch -> batch.map { it.id } },
        )
    }

    private fun chapters(count: Int): MutableList<Chapter> =
        (1..count)
            .map { Chapter(id = "c$it", title = "Chapter $it") }
            .toMutableList()

    private fun story(
        id: String,
        title: String = id,
        author: String = "",
        chapters: MutableList<Chapter> = mutableListOf(),
        bookmarkIndex: Int? = null,
        pending: MutableList<String>? = null,
        archived: Boolean = false,
        unavailable: Boolean = false,
        publicationStatus: PublicationStatus = PublicationStatus.unknown,
    ): Story =
        Story(
            id = id,
            title = title,
            author = author,
            sourceUrl = "https://example.com/$id",
            chapters = chapters,
            lastReadChapterId = bookmarkIndex?.let { chapters[it].id },
            pendingNewChapterIds = pending,
            isArchived = archived.takeIf { it },
            publicationStatus = publicationStatus,
            sourceSyncState =
                SourceSyncState(
                    availability = if (unavailable) SourceAvailability.not_found else SourceAvailability.available,
                ),
        )
}
