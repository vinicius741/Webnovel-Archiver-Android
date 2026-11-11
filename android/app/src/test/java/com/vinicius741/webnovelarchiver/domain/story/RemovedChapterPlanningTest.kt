package com.vinicius741.webnovelarchiver.domain.story

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.EpubConfig
import com.vinicius741.webnovelarchiver.domain.model.Story
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemovedChapterPlanningTest {
    private fun ch(
        id: String,
        removedAt: Long? = null,
        patreon: Boolean = false,
    ) = Chapter(
        id = id,
        title = id,
        url = "https://example.com/$id",
        downloaded = true,
        filePath = "$id.html",
        removedFromSourceAt = removedAt,
        patreonPostId = if (patreon) "p_$id" else null,
    )

    private fun ids(chapters: List<Chapter>) = chapters.map { it.id }

    @Test
    fun keptChaptersReturnToTheirOldPositionBetweenSurvivingAndNewChapters() {
        // Shape of a stubbed novel: samples survive, the stubbed run is gone, new chapters follow.
        val previous = listOf("1", "2", "stub", "158", "159", "160", "212").map { ch(it) }
        val current = listOf("1", "2", "stub", "158", "213", "214").map { ch(it) }
        val kept = listOf("159", "160", "212").map { ch(it, removedAt = 1L) }

        val placed = RemovedChapterPlanning.placeKept(previous, current, kept) { it.id }

        assertEquals(listOf("1", "2", "stub", "158", "159", "160", "212", "213", "214"), ids(placed))
    }

    @Test
    fun keptChaptersWithoutASurvivingPredecessorGoFirstAndPatreonCopiesStayLast() {
        val previous = listOf(ch("a"), ch("b"), ch("c"), ch("p", patreon = true))
        val current = listOf(ch("c"), ch("d"), ch("p", patreon = true))
        val kept = listOf(ch("a", 1L), ch("b", 1L))

        val placed = RemovedChapterPlanning.placeKept(previous, current, kept) { it.id }

        assertEquals(listOf("a", "b", "c", "d", "p"), ids(placed))
    }

    @Test
    fun aKeptChapterAfterTheLastSourceChapterStaysBeforePatreonCopies() {
        val previous = listOf(ch("a"), ch("b"))
        val current = listOf(ch("a"), ch("p", patreon = true))

        val placed = RemovedChapterPlanning.placeKept(previous, current, listOf(ch("b", 1L))) { it.id }

        assertEquals(listOf("a", "b", "p"), ids(placed))
    }

    @Test
    fun remapPositionsFollowsChaptersByIdentityAndKeepsAnOpenEndedEpubRange() {
        val before = listOf("a", "b", "c").map { ch(it) }
        val after = listOf("a", "x", "b", "y", "c").map { ch(it) }
        val story =
            Story(
                chapters = before.toMutableList(),
                epubConfig = EpubConfig(rangeStart = 2, rangeEnd = 3),
                aiContextChapterIndices = mutableListOf(1, 2),
                aiCoverContextChapterIndices = mutableListOf(0),
            )

        val remapped = RemovedChapterPlanning.remapPositions(story, before, after)

        assertEquals(3, remapped.epubConfig?.rangeStart)
        assertEquals(5, remapped.epubConfig?.rangeEnd)
        assertEquals(listOf(2, 4), remapped.aiContextChapterIndices)
        assertEquals(listOf(0), remapped.aiCoverContextChapterIndices)
    }

    @Test
    fun remapPositionsKeepsAClosedEpubRangeOnTheSameChapters() {
        val before = listOf("a", "b", "c", "d").map { ch(it) }
        val after = listOf("a", "x", "b", "c", "d").map { ch(it) }
        val story = Story(epubConfig = EpubConfig(rangeStart = 1, rangeEnd = 3))

        val remapped = RemovedChapterPlanning.remapPositions(story, before, after)

        assertEquals(1, remapped.epubConfig?.rangeStart)
        assertEquals(4, remapped.epubConfig?.rangeEnd)
    }

    @Test
    fun discardDropsOnlyRemovedChaptersAndMovesTheBookmarkBack() {
        val story =
            Story(
                id = "s",
                chapters = mutableListOf(ch("a"), ch("b", 1L), ch("c", 1L), ch("d")),
                lastReadChapterId = "c",
                epubPath = "s.epub",
            )

        val result = RemovedChapterPlanning.discard(story)

        assertEquals(listOf("a", "d"), ids(result.story.chapters))
        assertEquals(listOf("b", "c"), ids(result.removed))
        assertEquals("a", result.story.lastReadChapterId)
        assertEquals(2, result.story.totalChapters)
        assertTrue(result.story.epubStale == true)
    }

    @Test
    fun discardOfOneChapterLeavesTheOthers() {
        val story = Story(chapters = mutableListOf(ch("a", 1L), ch("b", 1L)), lastReadChapterId = "a")

        val result = RemovedChapterPlanning.discard(story, setOf("a"))

        assertEquals(listOf("b"), ids(result.story.chapters))
        assertNull(result.story.lastReadChapterId)
    }

    @Test
    fun restoreLostDownloadsPutsBackDownloadedChaptersACommitDropped() {
        val before = Story(id = "s", chapters = mutableListOf(ch("1"), ch("2"), ch("3"), ch("4")), lastReadChapterId = "3")
        val after = Story(id = "s", chapters = mutableListOf(ch("1"), ch("4"), ch("5")))

        val restored = RemovedChapterPlanning.restoreLostDownloads(before, after, { it.id }, removedAt = 9L)

        assertEquals(listOf("1", "2", "3", "4", "5"), ids(restored.chapters))
        assertEquals(listOf(null, 9L, 9L, null, null), restored.chapters.map { it.removedFromSourceAt })
        assertEquals(5, restored.totalChapters)
        assertEquals(5, restored.downloadedChapters)
        // A bookmark the dropped list lost comes back with its chapter.
        assertEquals("3", restored.lastReadChapterId)
    }

    @Test
    fun restoreLostDownloadsKeepsAnEarlierRemovalTime() {
        val before = Story(id = "s", chapters = mutableListOf(ch("1"), ch("2", removedAt = 3L)))
        val after = Story(id = "s", chapters = mutableListOf(ch("1")))

        val restored = RemovedChapterPlanning.restoreLostDownloads(before, after, { it.id }, removedAt = 9L)

        assertEquals(3L, restored.chapters.single { it.id == "2" }.removedFromSourceAt)
    }

    @Test
    fun restoreLostDownloadsIgnoresPatreonCopiesAndRowsWithoutText() {
        val notDownloaded = ch("2").copy(downloaded = false, filePath = null)
        val before = Story(id = "s", chapters = mutableListOf(ch("1"), notDownloaded, ch("p", patreon = true)))
        val after = Story(id = "s", chapters = mutableListOf(ch("1")))

        val restored = RemovedChapterPlanning.restoreLostDownloads(before, after, { it.id }, removedAt = 9L)

        assertTrue(restored === after)
    }

    @Test
    fun restoreLostDownloadsMatchesChaptersByStableKeyNotRawId() {
        // The commit re-keyed a chapter (same URL, new id); it was not lost.
        val before = Story(id = "s", chapters = mutableListOf(ch("1"), ch("old")))
        val after = Story(id = "s", chapters = mutableListOf(ch("1"), ch("old").copy(id = "new")))

        val restored = RemovedChapterPlanning.restoreLostDownloads(before, after, { it.url }, removedAt = 9L)

        assertTrue(restored === after)
    }
}
