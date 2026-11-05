package com.vinicius741.webnovelarchiver.domain.story

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.Story
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyArchiveFoldPlanningTest {
    private val byId: (Story) -> (Chapter) -> String = { { chapter -> chapter.id } }

    private fun ch(
        id: String,
        dir: String,
        downloaded: Boolean = true,
    ) = Chapter(
        id = id,
        title = "Chapter $id",
        url = "https://example.com/$id",
        downloaded = downloaded,
        filePath = if (downloaded) "novels/$dir/$id.html" else null,
    )

    private fun live(
        vararg chapters: Chapter,
        lastRead: String? = null,
    ) = Story(id = "rr_1", sourceUrl = "https://example.com", chapters = chapters.toMutableList(), lastReadChapterId = lastRead)

    private fun archive(
        suffix: String,
        archivedAt: Long,
        vararg chapterIds: String,
        lastRead: String? = null,
    ): Story {
        val id = "rr_1__archive_${archivedAt}_$suffix"
        return Story(
            id = id,
            chapters = chapterIds.map { ch(it, id) }.toMutableList(),
            isArchived = true,
            archiveOfStoryId = "rr_1",
            archivedAt = archivedAt,
            lastReadChapterId = lastRead,
        )
    }

    private fun Story.ids() = chapters.map { it.id }

    @Test
    fun foldsMissingChaptersIntoTheLiveStoryInReadingOrder() {
        val liveStory = live(ch("1", "rr_1"), ch("2", "rr_1"), ch("5", "rr_1"))
        val snapshot = archive("a", 100L, "1", "2", "3", "4")

        val plan = LegacyArchiveFoldPlanning.plan(listOf(liveStory, snapshot), byId) { true }.single()

        assertEquals(listOf("1", "2", "3", "4", "5"), plan.story.ids())
        assertEquals(listOf(null, null, 100L, 100L, null), plan.story.chapters.map { it.removedFromSourceAt })
        assertEquals(setOf("3", "4"), plan.adoptedChapterIds)
        assertEquals(listOf(snapshot.id), plan.archiveIds)
        assertFalse(plan.createsStory)
        assertEquals(5, plan.story.downloadedChapters)
    }

    @Test
    fun anIdenticalSnapshotFoldsToNothing() {
        val liveStory = live(ch("1", "rr_1"), ch("2", "rr_1"))
        val snapshot = archive("a", 100L, "1", "2")

        val plan = LegacyArchiveFoldPlanning.plan(listOf(liveStory, snapshot), byId) { true }.single()

        assertEquals(liveStory.chapters, plan.story.chapters)
        assertTrue(plan.adoptedChapterIds.isEmpty())
        assertEquals(listOf(snapshot.id), plan.archiveIds)
    }

    @Test
    fun aSnapshotCopyFillsALiveChapterThatWasNeverDownloaded() {
        val liveStory = live(ch("1", "rr_1"), ch("2", "rr_1", downloaded = false))
        val snapshot = archive("a", 100L, "1", "2")

        val plan = LegacyArchiveFoldPlanning.plan(listOf(liveStory, snapshot), byId) { true }.single()

        val filled = plan.story.chapters[1]
        assertTrue(filled.downloaded)
        assertNull(filled.removedFromSourceAt)
        assertEquals(setOf("2"), plan.adoptedChapterIds)
    }

    @Test
    fun aSnapshotCopyReplacesALiveChapterWhoseFileIsMissing() {
        val liveStory = live(ch("1", "rr_1"), ch("2", "rr_1"))
        val snapshot = archive("a", 100L, "1", "2")

        val plan =
            LegacyArchiveFoldPlanning
                .plan(listOf(liveStory, snapshot), byId) { it.filePath != "novels/rr_1/2.html" }
                .single()

        assertEquals("novels/${snapshot.id}/2.html", plan.story.chapters[1].filePath)
        assertEquals(setOf("2"), plan.adoptedChapterIds)
    }

    @Test
    fun undownloadedAndUnreadableSnapshotChaptersAreNotKept() {
        val snapshotId = "rr_1__archive_100_a"
        val snapshot =
            Story(
                id = snapshotId,
                isArchived = true,
                archiveOfStoryId = "rr_1",
                archivedAt = 100L,
                chapters =
                    mutableListOf(
                        ch("1", snapshotId),
                        ch("note", snapshotId, downloaded = false),
                        ch("lost", snapshotId),
                    ),
            )

        val plan =
            LegacyArchiveFoldPlanning
                .plan(listOf(live(ch("1", "rr_1")), snapshot), byId) { it.id != "lost" }
                .single()

        assertEquals(listOf("1"), plan.story.ids())
    }

    @Test
    fun aSnapshotPatreonCopyIsSkippedWhenTheLiveStoryHasItsPublicChapter() {
        val snapshotId = "rr_1__archive_100_a"
        val publicChapter = ch("1", "rr_1").copy(title = "Chapter 5 - The Road")
        val copy = ch("patreon_9", snapshotId).copy(title = "Chapter 5 - The Road", patreonPostId = "9")
        val snapshot =
            Story(id = snapshotId, isArchived = true, archiveOfStoryId = "rr_1", archivedAt = 100L, chapters = mutableListOf(copy))

        val plan = LegacyArchiveFoldPlanning.plan(listOf(live(publicChapter), snapshot), byId) { true }.single()

        assertEquals(listOf("1"), plan.story.ids())
        assertTrue(LegacyArchiveFoldPlanning.coverage(plan.story, { it.id }) { true }(copy))
    }

    @Test
    fun aSnapshotPatreonCopyWithNoOtherTextIsKeptAsARemovedChapterBeforeLivePatreonCopies() {
        val snapshotId = "rr_1__archive_100_a"
        val copy = ch("patreon_9", snapshotId).copy(title = "Chapter 6 - Early", patreonPostId = "9", patreonReplacedBy = "x")
        val snapshot =
            Story(
                id = snapshotId,
                isArchived = true,
                archiveOfStoryId = "rr_1",
                archivedAt = 100L,
                chapters = mutableListOf(ch("1", snapshotId), copy),
            )
        val livePatreon = ch("patreon_10", "rr_1").copy(title = "Chapter 7 - Later", patreonPostId = "10")

        val plan = LegacyArchiveFoldPlanning.plan(listOf(live(ch("1", "rr_1"), livePatreon), snapshot), byId) { true }.single()

        assertEquals(listOf("1", "patreon_9", "patreon_10"), plan.story.ids())
        val kept = plan.story.chapters[1]
        assertNull(kept.patreonPostId)
        assertNull(kept.patreonReplacedBy)
        assertEquals(100L, kept.removedFromSourceAt)
        assertEquals(setOf("patreon_9"), plan.adoptedChapterIds)
    }

    @Test
    fun aSnapshotPatreonCopyFillsTheSameLiveCopyInsteadOfDuplicatingIt() {
        val snapshotId = "rr_1__archive_100_a"
        val copy = ch("patreon_9", snapshotId).copy(title = "Chapter 6 - Early", patreonPostId = "9")
        val snapshot =
            Story(id = snapshotId, isArchived = true, archiveOfStoryId = "rr_1", archivedAt = 100L, chapters = mutableListOf(copy))
        val liveCopy = ch("patreon_9", "rr_1", downloaded = false).copy(title = "Chapter 6 - Early", patreonPostId = "9")

        val plan = LegacyArchiveFoldPlanning.plan(listOf(live(ch("1", "rr_1"), liveCopy), snapshot), byId) { true }.single()

        assertEquals(listOf("1", "patreon_9"), plan.story.ids())
        assertTrue(plan.story.chapters[1].downloaded)
        assertEquals("9", plan.story.chapters[1].patreonPostId)
    }

    @Test
    fun severalSnapshotsFoldNewestFirstWithTheTimeOfTheLastSnapshotHoldingEachChapter() {
        val liveStory = live(ch("1", "rr_1"))
        val older = archive("old", 100L, "1", "2", "3")
        val newer = archive("new", 200L, "1", "3")

        val plan = LegacyArchiveFoldPlanning.plan(listOf(liveStory, older, newer), byId) { true }.single()

        assertEquals(listOf("1", "2", "3"), plan.story.ids())
        assertEquals(listOf(null, 100L, 200L), plan.story.chapters.map { it.removedFromSourceAt })
        assertEquals(setOf(older.id, newer.id), plan.archiveIds.toSet())
    }

    @Test
    fun aSnapshotRestoresABookmarkTheSourceRemovalCleared() {
        val plan =
            LegacyArchiveFoldPlanning
                .plan(listOf(live(ch("1", "rr_1")), archive("a", 100L, "1", "2", lastRead = "2")), byId) { true }
                .single()

        assertEquals("2", plan.story.lastReadChapterId)
    }

    @Test
    fun aSnapshotWithoutItsLiveStoryBecomesTheLiveStoryAgain() {
        val older = archive("old", 100L, "1", "2")
        val newer = archive("new", 200L, "1", "3")

        val plan = LegacyArchiveFoldPlanning.plan(listOf(older, newer), byId) { true }.single()

        assertTrue(plan.createsStory)
        assertEquals("rr_1", plan.story.id)
        assertNull(plan.story.isArchived)
        assertNull(plan.story.archiveOfStoryId)
        assertEquals(listOf("1", "2", "3"), plan.story.ids())
        assertEquals(listOf(null, 100L, null), plan.story.chapters.map { it.removedFromSourceAt })
        assertEquals(setOf("1", "2", "3"), plan.adoptedChapterIds)
    }

    @Test
    fun aLibraryWithoutSnapshotsHasNoPlans() {
        assertTrue(LegacyArchiveFoldPlanning.plan(listOf(live(ch("1", "rr_1"))), byId) { true }.isEmpty())
    }
}
