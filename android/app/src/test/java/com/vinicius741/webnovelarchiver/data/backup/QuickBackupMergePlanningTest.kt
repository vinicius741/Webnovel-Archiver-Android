package com.vinicius741.webnovelarchiver.data.backup

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.DownloadStatus
import com.vinicius741.webnovelarchiver.domain.model.EpubConfig
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.model.StoryMetricHistory
import com.vinicius741.webnovelarchiver.domain.model.StoryMetricSnapshot
import com.vinicius741.webnovelarchiver.domain.model.Tab
import com.vinicius741.webnovelarchiver.domain.model.TtsStoryPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickBackupMergePlanningTest {
    /** Chapters whose text is on "this phone" in these tests. */
    private val onPhone = mutableSetOf<String>()

    private fun merge(
        backup: List<Story>,
        local: List<Story>,
        files: Set<String> = emptySet(),
        covers: Map<String, String> = emptyMap(),
    ) = QuickBackupMergePlanning.mergeLibrary(
        backup = backup,
        local = local,
        hasLocalText = { it.filePath in onPhone },
        fileExists = { it in files },
        backupCovers = covers,
    )

    private fun downloaded(id: String) =
        Chapter(id = id, title = id, downloaded = true, filePath = "novels/s/$id.html", downloadedAt = 5L).also {
            onPhone +=
                "novels/s/$id.html"
        }

    @Test
    fun metadataComesFromTheBackupAndChapterTextStays() {
        val local =
            Story(
                id = "s",
                title = "Old title",
                lastReadChapterId = "c1",
                tabId = "tab-a",
                chapters = mutableListOf(downloaded("c1"), downloaded("c2"), Chapter(id = "c3")),
                epubPath = "epubs/s/s.epub",
                status = DownloadStatus.partial,
            )
        val backup =
            Story(
                id = "s",
                title = "New title",
                lastReadChapterId = "c3",
                tabId = "tab-b",
                chapters = mutableListOf(Chapter(id = "c1", downloaded = true), Chapter(id = "c2"), Chapter(id = "c3")),
            )

        val plan = merge(listOf(backup), listOf(local))
        val story = plan.stories.single()

        assertEquals("New title", story.title)
        assertEquals("c3", story.lastReadChapterId)
        assertEquals("tab-b", story.tabId)
        assertEquals(listOf("c1", "c2", "c3"), story.chapters.map { it.id })
        assertEquals(
            "a chapter with text here stays downloaded even when the backup predates it",
            "novels/s/c2.html",
            story.chapters[1].filePath,
        )
        assertTrue(story.chapters[1].downloaded)
        assertEquals(5L, story.chapters[1].downloadedAt)
        assertEquals(2, story.downloadedChapters)
        assertEquals(3, story.totalChapters)
        assertEquals("epubs/s/s.epub", story.epubPath)
        assertEquals(DownloadStatus.partial, story.status)
        assertEquals(QuickBackupMergePlanning.Counts(updated = 1), plan.counts)
    }

    @Test
    fun aChapterDownloadedInTheBackupButMissingHereCanBeDownloadedAgain() {
        val local = Story(id = "s", chapters = mutableListOf(Chapter(id = "c1", downloaded = true, filePath = "novels/s/gone.html")))
        val backup =
            Story(
                id = "s",
                chapters = mutableListOf(Chapter(id = "c1", downloaded = true, downloadedAt = 3L), Chapter(id = "c2", downloaded = true)),
            )

        val plan = merge(listOf(backup), listOf(local))
        val chapters = plan.stories.single().chapters

        assertTrue(chapters.none { it.downloaded || it.filePath != null || it.downloadedAt != null })
        assertEquals(2, plan.counts.toDownloadAgain)
        assertEquals(0, plan.stories.single().downloadedChapters)
    }

    @Test
    fun downloadedChaptersTheBackupDoesNotListStayInTheirPlace() {
        val removed = downloaded("c2").copy(removedFromSourceAt = 9L)
        val newer = downloaded("c4")
        val local = Story(id = "s", chapters = mutableListOf(downloaded("c1"), removed, downloaded("c3"), newer, Chapter(id = "c5")))
        val backup = Story(id = "s", chapters = mutableListOf(Chapter(id = "c1"), Chapter(id = "c3")))

        val plan = merge(listOf(backup), listOf(local))
        val story = plan.stories.single()

        assertEquals(
            "not-downloaded chapters only on this phone come back with the next sync",
            listOf(
                "c1",
                "c2",
                "c3",
                "c4",
            ),
            story.chapters.map {
                it.id
            },
        )
        assertEquals(9L, story.chapters[1].removedFromSourceAt)
        assertNull(story.chapters[3].removedFromSourceAt)
        assertEquals(4, story.downloadedChapters)
        assertEquals(2, plan.counts.keptUnlistedChapters)
    }

    @Test
    fun anOlderBackupNeverUnflagsARemovedChapter() {
        val local =
            Story(
                id = "s",
                chapters = mutableListOf(downloaded("c1").copy(removedFromSourceAt = 9L), Chapter(id = "c2", removedFromSourceAt = 7L)),
            )
        val backup = Story(id = "s", chapters = mutableListOf(Chapter(id = "c1"), Chapter(id = "c2")))

        val chapters = merge(listOf(backup), listOf(local)).stories.single().chapters

        assertEquals(listOf(9L, 7L), chapters.map { it.removedFromSourceAt })
    }

    @Test
    fun aRemovedChapterInTheBackupIsKeptEvenWithoutTextHere() {
        val backup = Story(id = "s", chapters = mutableListOf(Chapter(id = "c1", downloaded = true, removedFromSourceAt = 4L)))

        val plan = merge(listOf(backup), emptyList())
        val chapter =
            plan.stories
                .single()
                .chapters
                .single()

        assertEquals(4L, chapter.removedFromSourceAt)
        assertFalse(chapter.downloaded)
        assertEquals(1, plan.counts.toDownloadAgain)
    }

    @Test
    fun storiesOnlyOnThisPhoneStayAndNewOnesAreAdded() {
        val mine = Story(id = "mine", chapters = mutableListOf(downloaded("m1")))
        val plan = merge(listOf(Story(id = "new", status = DownloadStatus.completed)), listOf(mine))

        assertEquals(listOf("new", "mine"), plan.stories.map { it.id })
        assertTrue(plan.stories[1] === mine)
        assertEquals(DownloadStatus.idle, plan.stories[0].status)
        assertEquals(QuickBackupMergePlanning.Counts(added = 1, keptLocalOnly = 1), plan.counts)
    }

    @Test
    fun epubRangeFollowsTheChaptersWhenLocalOnesAreInserted() {
        val local = Story(id = "s", chapters = mutableListOf(downloaded("c1"), downloaded("x"), downloaded("c2"), downloaded("c3")))
        val backup =
            Story(
                id = "s",
                chapters = mutableListOf(Chapter(id = "c1"), Chapter(id = "c2"), Chapter(id = "c3")),
                epubConfig = EpubConfig(rangeStart = 2, rangeEnd = 2),
            )

        val story = merge(listOf(backup), listOf(local)).stories.single()

        assertEquals(listOf("c1", "x", "c2", "c3"), story.chapters.map { it.id })
        assertEquals(3, story.epubConfig?.rangeStart)
        assertEquals(3, story.epubConfig?.rangeEnd)
    }

    @Test
    fun aCoverOnThisPhoneIsNeverReplaced() {
        val local = Story(id = "s", aiCoverPath = "covers/s.png", showAiCover = false)
        val backup = Story(id = "s", aiCoverPath = "covers/s.jpg", showAiCover = true)

        val plan = merge(listOf(backup), listOf(local), files = setOf("covers/s.png"), covers = mapOf("s" to "covers/s.jpg"))

        assertEquals("covers/s.png", plan.stories.single().aiCoverPath)
        assertFalse(plan.stories.single().showAiCover)
        assertTrue(plan.coversToWrite.isEmpty())
    }

    @Test
    fun theBackupsCoverIsWrittenOnlyWhenItIsNotHere() {
        val backup = Story(id = "s", aiCoverPath = "covers/s.jpg")

        val carried = merge(listOf(backup), emptyList(), covers = mapOf("s" to "covers/s.jpg"))
        assertEquals(mapOf("s" to "covers/s.jpg"), carried.coversToWrite)
        assertEquals("covers/s.jpg", carried.stories.single().aiCoverPath)

        val alreadyHere = merge(listOf(backup), emptyList(), files = setOf("covers/s.jpg"), covers = mapOf("s" to "covers/s.jpg"))
        assertTrue(alreadyHere.coversToWrite.isEmpty())
        assertEquals("covers/s.jpg", alreadyHere.stories.single().aiCoverPath)

        val missing = merge(listOf(backup), emptyList())
        assertNull("no cover anywhere falls back to the source cover", missing.stories.single().aiCoverPath)
        assertFalse(missing.stories.single().showAiCover)
    }

    @Test
    fun tabsAndPositionsMergeWithoutLosingThisPhonesOwn() {
        val tabs =
            QuickBackupMergePlanning.mergeTabs(
                backup = listOf(Tab(id = "b", name = "Backup B", order = 1), Tab(id = "a", name = "Renamed A", order = 0)),
                local = listOf(Tab(id = "a", name = "A", order = 0), Tab(id = "c", name = "Local C", order = 1)),
            )
        assertEquals(listOf("a" to "Renamed A", "b" to "Backup B", "c" to "Local C"), tabs.map { it.id to it.name })
        assertEquals(listOf(0, 1, 2), tabs.map { it.order })

        val positions =
            QuickBackupMergePlanning.mergeTtsPositions(
                backup = mapOf("s" to TtsStoryPosition(storyId = "s", chapterId = "backup")),
                local = mapOf("s" to TtsStoryPosition(storyId = "s", chapterId = "local"), "t" to TtsStoryPosition(storyId = "t")),
            )
        assertEquals("backup", positions.getValue("s").chapterId)
        assertTrue("t" in positions)
        assertEquals(listOf(Tab(id = "x")), QuickBackupMergePlanning.mergeTabs(null, listOf(Tab(id = "x"))))
    }

    @Test
    fun theNewerTrendHistoryWins() {
        fun history(vararg at: Long) = StoryMetricHistory("s", at.map { StoryMetricSnapshot(capturedAt = it) }.toMutableList())

        assertTrue(QuickBackupMergePlanning.backupHistoryIsNewer(history(1, 5), history(1, 4)))
        assertTrue(QuickBackupMergePlanning.backupHistoryIsNewer(history(1), null))
        assertFalse(QuickBackupMergePlanning.backupHistoryIsNewer(history(1, 4), history(1, 4)))
        assertFalse(QuickBackupMergePlanning.backupHistoryIsNewer(history(3), history(9)))
        assertFalse(QuickBackupMergePlanning.backupHistoryIsNewer(history(), null))
    }

    @Test
    fun theSummarySaysTextWasKeptAndWhatCanBeDownloadedAgain() {
        assertEquals(
            "Restored quick backup: 3 novels (1 new). Chapter text on this phone was kept; 12 chapters can be downloaded again.",
            QuickBackupMergePlanning.summary(QuickBackupMergePlanning.Counts(updated = 2, added = 1, toDownloadAgain = 12)),
        )
        assertEquals(
            "Restored quick backup: 1 novel. Chapter text on this phone was kept.",
            QuickBackupMergePlanning.summary(QuickBackupMergePlanning.Counts(updated = 1)),
        )
    }
}
