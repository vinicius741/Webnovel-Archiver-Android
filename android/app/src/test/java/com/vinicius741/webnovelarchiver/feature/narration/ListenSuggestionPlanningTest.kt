package com.vinicius741.webnovelarchiver.feature.narration

import org.junit.Assert.assertEquals
import org.junit.Test

class ListenSuggestionPlanningTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 100 * day

    private fun story(
        id: String,
        chapters: Int,
        bookmark: Int,
        audio: IntRange,
        listenedDaysAgo: Int?,
    ) = StoryAudioFacts(
        storyId = id,
        title = id.uppercase(),
        chapterIds = List(chapters) { "$id$it" },
        bookmarkIndex = bookmark,
        bytes = audio.associate { "$id$it" to 10L },
        lastListenedAt = listenedDaysAgo?.let { now - it * day },
    )

    @Test
    fun finishedStoryLosesAllItsAudio() {
        val result = ListenSuggestionPlanning.suggestions(listOf(story("a", 5, 4, 0..4, 1)), now)
        assertEquals(1, result.size)
        assertEquals(ListenSuggestion.Kind.FINISHED, result[0].kind)
        assertEquals(5, result[0].chapterCount)
        assertEquals(50L, result[0].bytes)
    }

    @Test
    fun idleStoryKeepsTheChaptersFromItsBookmark() {
        val result = ListenSuggestionPlanning.suggestions(listOf(story("b", 40, 10, 0..30, 20)), now)
        assertEquals(ListenSuggestion.Kind.ON_BREAK, result.single().kind)
        assertEquals(20, result.single().idleDays)
        // Keeps 10–19, deletes 0–9 and 20–30.
        assertEquals((0..9).map { "b$it" } + (20..30).map { "b$it" }, result.single().chapters["b"])
    }

    @Test
    fun activeStoriesShareOneHeardSuggestion() {
        val result =
            ListenSuggestionPlanning.suggestions(
                listOf(story("c", 20, 5, 3..8, 0), story("d", 20, 2, 0..1, 2), story("e", 20, 0, 0..4, 1)),
                now,
            )
        val heard = result.single()
        assertEquals(ListenSuggestion.Kind.HEARD, heard.kind)
        assertEquals(listOf("C", "D"), heard.titles)
        assertEquals(mapOf("c" to listOf("c3", "c4"), "d" to listOf("d0", "d1")), heard.chapters)
        assertEquals(40L, heard.bytes)
    }

    @Test
    fun nothingToFreeMeansNoSuggestion() {
        assertEquals(
            emptyList<ListenSuggestion>(),
            ListenSuggestionPlanning.suggestions(listOf(story("f", 20, 3, 5..9, 30).copy(lastListenedAt = null)), now),
        )
    }

    @Test
    fun heardStopsAtTheListeningPlaceWhenItIsBehindTheBookmark() {
        // Bookmark read ahead to index 15, listening stopped at index 6: only 0–5 count as heard.
        val result = ListenSuggestionPlanning.suggestions(listOf(story("g", 20, 15, 0..12, 1).copy(listenIndex = 6)), now)
        assertEquals((0..5).map { "g$it" }, result.single().chapters["g"])
    }

    @Test
    fun aBreakAnchorsOnTheListeningPlaceAndTakesQueuedChapters() {
        val idle = story("h", 40, -1, 0..30, 30).copy(listenIndex = 20, queued = setOf("h35"))
        val result = ListenSuggestionPlanning.suggestions(listOf(idle), now)
        // Keeps 20–29; deletes 0–19, 30 and the queued 35.
        assertEquals((0..19).map { "h$it" } + listOf("h30", "h35"), result.single().chapters["h"])
    }

    @Test
    fun noBookmarkAndNoListeningPlaceMeansNoSuggestion() {
        assertEquals(emptyList<ListenSuggestion>(), ListenSuggestionPlanning.suggestions(listOf(story("i", 20, -1, 0..9, 30)), now))
    }

    @Test
    fun listeningBehindALastChapterBookmarkIsNotFinished() {
        val result = ListenSuggestionPlanning.suggestions(listOf(story("j", 10, 9, 0..9, 1).copy(listenIndex = 4)), now)
        assertEquals(ListenSuggestion.Kind.HEARD, result.single().kind)
    }
}
