package com.vinicius741.webnovelarchiver.feature.details

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ChapterActionPlanningTest {
    private val chapters = List(10) { Chapter(id = "c$it", title = "Chapter ${it + 1}", downloaded = it < 6) }

    // Audio on chapters 2–8 (indexes 1–7): 1–5 ready, 6 generating, 7 queued.
    private val audio =
        (1..7).associate { index ->
            "c$index" to
                when (index) {
                    6 -> ChapterAudioInfo(ChapterAudioState.GENERATING)
                    7 -> ChapterAudioInfo(ChapterAudioState.QUEUED)
                    else -> ChapterAudioInfo(ChapterAudioState.READY, bytes = 10L * index, durationMs = 1000L)
                }
        }

    @Test
    fun heardIsEverythingWithAudioBeforeTheBookmark() {
        assertEquals(listOf("c1", "c2", "c3"), ChapterActionPlanning.heard(chapters, bookmarkIndex = 4, audio = audio))
        assertEquals(emptyList<String>(), ChapterActionPlanning.heard(chapters, bookmarkIndex = -1, audio = audio))
        assertEquals(emptyList<String>(), ChapterActionPlanning.heard(chapters, bookmarkIndex = 0, audio = audio))
    }

    @Test
    fun takingABreakKeepsTheChaptersFromTheBookmark() {
        // Bookmark on index 4, keep 2: indexes 4 and 5 stay; queued 7 leaves too.
        assertEquals(
            listOf("c1", "c2", "c3", "c6", "c7"),
            ChapterActionPlanning.breakDeletions(chapters, bookmarkIndex = 4, keep = 2, audio = audio),
        )
        // Without an anchor there is no place to keep, so nothing is deleted.
        assertEquals(emptyList<String>(), ChapterActionPlanning.breakDeletions(chapters, -1, 3, audio))
    }

    @Test
    fun anchorIsTheEarlierOfBookmarkAndListeningPlace() {
        assertEquals(119, ChapterActionPlanning.anchorIndex(bookmarkIndex = 199, listenIndex = 119))
        assertEquals(50, ChapterActionPlanning.anchorIndex(bookmarkIndex = 50, listenIndex = 80))
        assertEquals(149, ChapterActionPlanning.anchorIndex(bookmarkIndex = -1, listenIndex = 149))
        assertEquals(7, ChapterActionPlanning.anchorIndex(bookmarkIndex = 7, listenIndex = -1))
        assertEquals(-1, ChapterActionPlanning.anchorIndex(bookmarkIndex = -1, listenIndex = -1))
    }

    @Test
    fun allAudioIncludesQueuedAndGenerating() {
        assertEquals((1..7).map { "c$it" }, ChapterActionPlanning.allAudio(chapters, audio))
        assertEquals(2, ChapterActionPlanning.pendingOf(ChapterActionPlanning.allAudio(chapters, audio), audio))
    }

    @Test
    fun nextToGenerateSkipsChaptersWithAudioOrJobs() {
        val next = ChapterActionPlanning.nextToGenerate(chapters, fromIndex = 4, count = 2, audio = audio) { true }
        assertEquals(listOf("c8", "c9"), next)
        val playableOnly = ChapterActionPlanning.nextToGenerate(chapters, 0, 5, audio) { it.downloaded }
        assertEquals(listOf("c0"), playableOnly)
    }

    @Test
    fun nextToDownloadSkipsDownloadedAndDownloading() {
        assertEquals(listOf(7, 8), ChapterActionPlanning.nextToDownload(chapters, fromIndex = 2, count = 2, downloading = setOf("c6")))
    }

    @Test
    fun summaryCountsReadyPendingAndHeard() {
        val summary = ChapterActionPlanning.summary(chapters, bookmarkIndex = 3, audio = audio)
        assertEquals(StoryAudioSummary(ready = 5, pending = 2, bytes = 150L, durationMs = 5000L, heard = 2), summary)
    }

    @Test
    fun statesFollowTextAndAudio() {
        assertArrayEquals(
            byteArrayOf(1, 1, 1, 1, 1, 1, 2, 3, 0, 0),
            ChapterActionPlanning.textStates(chapters, downloading = setOf("c6"), waiting = setOf("c7")),
        )
        assertArrayEquals(
            byteArrayOf(0, 1, 1, 1, 1, 1, 3, 2, 0, 0),
            ChapterActionPlanning.audioStates(chapters, audio),
        )
    }

    @Test
    fun rangeLabelJoinsRunsAndCapsAtThree() {
        assertEquals("Ch. 1–3, 5", ChapterActionPlanning.rangeLabel(listOf(2, 0, 1, 4)))
        assertEquals("Ch. 1, 3, 5 +1 more", ChapterActionPlanning.rangeLabel(listOf(0, 2, 4, 6)))
        assertEquals("", ChapterActionPlanning.rangeLabel(emptyList()))
    }
}
