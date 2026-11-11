package com.vinicius741.webnovelarchiver.domain.story

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.Story
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupChapterRecoveryPlanningTest {
    private val byId: (Story) -> (Chapter) -> String = { { chapter -> chapter.id } }
    private val takenAt = 1_000L

    private fun ch(
        id: String,
        dir: String,
        downloaded: Boolean = true,
        title: String = "$id - Chapter",
    ) = Chapter(
        id = id,
        title = title,
        url = "https://example.com/$id",
        downloaded = downloaded,
        filePath = if (downloaded) "/backup/novels/$dir/$id.html" else null,
    )

    private fun story(
        id: String,
        vararg chapters: Chapter,
    ) = Story(id = id, title = "Novel $id", sourceUrl = "https://example.com/$id", chapters = chapters.toMutableList())

    private fun plan(
        live: List<Story>,
        backup: List<Story>,
        hasContent: (Chapter) -> Boolean = { true },
    ) = BackupChapterRecoveryPlanning.plan(live, backup, takenAt, byId, hasContent)

    @Test
    fun chaptersTheLiveNovelLostComeBackFlaggedInReadingOrder() {
        // The Archmage case: the source dropped 73-160, the live novel lost them, the backup has them.
        val live = story("rr_1", ch("1", "rr_1"), ch("4", "rr_1"), ch("ann", "rr_1"), ch("161", "rr_1"))
        val backup =
            story("rr_1", ch("1", "b"), ch("4", "b"), ch("73", "b"), ch("143", "b"), ch("ann", "b"), ch("144", "b"), ch("160", "b"))

        val result = plan(listOf(live), listOf(backup))

        val recovered = result.stories.single()
        assertEquals(listOf("1", "4", "73", "143", "ann", "144", "160", "161"), recovered.story.chapters.map { it.id })
        assertEquals(
            setOf("73", "143", "144", "160"),
            recovered.story.chapters
                .filter { it.isRemovedFromSource }
                .mapTo(HashSet()) { it.id },
        )
        assertTrue(
            recovered.story.chapters
                .filter { it.isRemovedFromSource }
                .all { it.removedFromSourceAt == takenAt },
        )
        assertEquals(setOf("73", "143", "144", "160"), recovered.adoptedChapterIds)
        assertEquals(4, recovered.restoredRemoved)
        assertEquals(0, recovered.filled)
        assertEquals(8, recovered.story.downloadedChapters)
        assertEquals(8, recovered.story.totalChapters)
    }

    @Test
    fun aLiveRowWithoutTextTakesTheBackupCopy() {
        val live = story("rr_1", ch("1", "rr_1"), ch("2", "rr_1", downloaded = false))
        val backup = story("rr_1", ch("1", "b"), ch("2", "b"))

        val recovered = plan(listOf(live), listOf(backup)).stories.single()

        assertEquals(listOf("1", "2"), recovered.story.chapters.map { it.id })
        assertTrue(recovered.story.chapters.none { it.isRemovedFromSource })
        assertEquals("/backup/novels/b/2.html", recovered.story.chapters[1].filePath)
        assertEquals(setOf("2"), recovered.adoptedChapterIds)
        assertEquals(1, recovered.filled)
        assertEquals(0, recovered.restoredRemoved)
    }

    @Test
    fun aLiveRowWhoseFileIsMissingTakesTheBackupCopy() {
        val live = story("rr_1", ch("1", "rr_1"), ch("2", "rr_1"))
        val backup = story("rr_1", ch("1", "b"), ch("2", "b"))

        val recovered = plan(listOf(live), listOf(backup)) { it.filePath != "/backup/novels/rr_1/2.html" }.stories.single()

        assertEquals("/backup/novels/b/2.html", recovered.story.chapters[1].filePath)
        assertEquals(1, recovered.filled)
    }

    @Test
    fun nothingToRecoverWhenTheLiveNovelAlreadyHasEveryChapter() {
        val live = story("rr_1", ch("1", "rr_1"), ch("2", "rr_1"), ch("3", "rr_1"))
        val backup = story("rr_1", ch("1", "b"), ch("2", "b"))

        val result = plan(listOf(live), listOf(backup))

        assertTrue(result.stories.isEmpty())
        assertEquals(0, result.recoveredChapters)
        assertTrue(BackupChapterRecoveryPlanning.summary(result).startsWith("Nothing to recover"))
    }

    @Test
    fun novelsNoLongerInTheLibraryAreSkippedNotRecreated() {
        val live = story("rr_1", ch("1", "rr_1"))
        val deleted = story("rr_2", ch("1", "b"))

        val result = plan(listOf(live), listOf(deleted))

        assertTrue(result.stories.isEmpty())
        assertEquals(listOf("rr_2"), result.skippedMissingStoryIds)
    }

    @Test
    fun aBackupNovelWithoutReadableChaptersIsNotReportedAsSkipped() {
        val result = plan(listOf(story("rr_1", ch("1", "rr_1"))), listOf(story("rr_2", ch("1", "b", downloaded = false))))

        assertTrue(result.skippedMissingStoryIds.isEmpty())
    }

    @Test
    fun legacyArchiveSnapshotsInsideTheBackupFoldIntoTheirLiveNovel() {
        val live = story("rr_1", ch("1", "rr_1"), ch("5", "rr_1"))
        val backupLive = story("rr_1", ch("1", "b"), ch("5", "b"))
        val snapshotId = "rr_1__archive_500_abc"
        val snapshot =
            story(snapshotId, ch("1", snapshotId), ch("2", snapshotId), ch("3", snapshotId), ch("5", snapshotId))
                .copy(isArchived = true, archiveOfStoryId = "rr_1", archivedAt = 500L)

        val recovered = plan(listOf(live), listOf(backupLive, snapshot)).stories.single()

        assertEquals(listOf("1", "2", "3", "5"), recovered.story.chapters.map { it.id })
        // Chapters from a snapshot keep the time that snapshot was taken.
        assertEquals(listOf(null, 500L, 500L, null), recovered.story.chapters.map { it.removedFromSourceAt })
        assertEquals(2, recovered.restoredRemoved)
        assertEquals(2, recovered.sources.size)
    }

    @Test
    fun chaptersAlreadyFlaggedInTheBackupKeepTheirOriginalRemovalTime() {
        val live = story("rr_1", ch("1", "rr_1"))
        val backup = story("rr_1", ch("1", "b"), ch("2", "b").copy(removedFromSourceAt = 42L))

        val recovered = plan(listOf(live), listOf(backup)).stories.single()

        assertEquals(
            42L,
            recovered.story.chapters
                .single { it.id == "2" }
                .removedFromSourceAt,
        )
    }

    @Test
    fun backupChaptersWithoutReadableTextAreNotRecovered() {
        val live = story("rr_1", ch("1", "rr_1"))
        val backup = story("rr_1", ch("1", "b"), ch("2", "b"))

        val result = plan(listOf(live), listOf(backup)) { it.filePath != "/backup/novels/b/2.html" }

        assertTrue(result.stories.isEmpty())
    }

    @Test
    fun summaryCountsChaptersAndNovels() {
        val result =
            plan(
                listOf(story("rr_1", ch("1", "rr_1")), story("rr_2", ch("1", "rr_2"))),
                listOf(story("rr_1", ch("1", "b"), ch("2", "b"), ch("3", "b")), story("rr_2", ch("1", "c"), ch("2", "c"))),
            )

        assertEquals(3, result.recoveredChapters)
        assertEquals("Recovered 3 chapters in 2 novels", BackupChapterRecoveryPlanning.summary(result))
    }
}
