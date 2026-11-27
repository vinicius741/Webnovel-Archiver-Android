package com.vinicius741.webnovelarchiver.feature.library

import androidx.recyclerview.widget.ListUpdateCallback
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.PatreonRawStats
import com.vinicius741.webnovelarchiver.domain.model.Story
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LibraryCardDiffTest {
    @Test
    fun downloadProgressAloneNotifiesNothing() {
        val before = listOf(story("a"), story("b", downloaded = 3), story("c"))
        val after = listOf(before[0], before[1].copy(downloadedChapters = 4, chapters = chapters(4)), before[2])

        assertEquals(emptyList<String>(), updates(before, after))
    }

    @Test
    fun unchangedInstancesNotifyNothing() {
        val stories = listOf(story("a"), story("b"))

        assertEquals(emptyList<String>(), updates(stories, stories.toList()))
    }

    @Test
    fun aShownFieldChangeRebindsOnlyThatCard() {
        val before = listOf(story("a"), story("b"), story("c"))
        val after = listOf(before[0], before[1].copy(title = "Renamed"), before[2])

        assertEquals(listOf("changed 1+1"), updates(before, after))
    }

    @Test
    fun aStoryRisingToTheTopIsOneMoveWithoutRebinds() {
        val before = listOf(story("a"), story("b"), story("c"), story("d"))
        val after = listOf(before[3], before[0], before[1], before[2])

        assertEquals(listOf("moved 3->0"), updates(before, after))
    }

    @Test
    fun filteringRemovesAndAddsOnlyTheAffectedCards() {
        val a = story("a")
        val b = story("b")
        val c = story("c")
        val d = story("d")

        assertEquals(listOf("removed 3+1", "removed 1+1"), updates(listOf(a, b, c, d), listOf(a, c)))
        assertEquals(setOf("inserted"), updates(listOf(a, c), listOf(a, b, c, d)).map { it.substringBefore(' ') }.toSet())
        assertEquals(2, updates(listOf(a, c), listOf(a, b, c, d)).size)
    }

    @Test
    fun cardContentIgnoresProgressButSeesEveryShownField() {
        val base = story("a", downloaded = 1)
        val now = 1_000L
        val content = LibraryCardContent.of(base, now)

        assertEquals(content, LibraryCardContent.of(base.copy(downloadedChapters = 2, chapters = chapters(2)), now))
        listOf(
            base.copy(title = "Other"),
            base.copy(author = "Someone"),
            base.copy(coverUrl = "https://example.com/other.jpg"),
            base.copy(aiCoverPath = "covers/a.png"),
            base.copy(score = "4.9"),
            base.copy(tags = mutableListOf("Action")),
            base.copy(patreonStats = PatreonRawStats(paidMembers = 10)),
            base.copy(totalChapters = 0),
            base.copy(sourceUrl = "https://www.scribblehub.com/series/1/a"),
        ).forEach { changed -> assertNotEquals(changed.toString(), content, LibraryCardContent.of(changed, now)) }
    }

    @Test
    fun tagsBeyondThePreviewDoNotRebind() {
        val base = story("a", tags = mutableListOf("1", "2", "3", "4", "5"))
        val now = 1_000L

        assertEquals(
            LibraryCardContent.of(base, now),
            LibraryCardContent.of(base.copy(tags = mutableListOf("1", "2", "3", "4", "6")), now),
        )
    }

    private fun updates(
        old: List<Story>,
        new: List<Story>,
    ): List<String> {
        val events = mutableListOf<String>()
        LibraryCardDiff.calculate(old, new, now = 1_000L).dispatchUpdatesTo(
            object : ListUpdateCallback {
                override fun onInserted(
                    position: Int,
                    count: Int,
                ) {
                    events += "inserted $position+$count"
                }

                override fun onRemoved(
                    position: Int,
                    count: Int,
                ) {
                    events += "removed $position+$count"
                }

                override fun onMoved(
                    fromPosition: Int,
                    toPosition: Int,
                ) {
                    events += "moved $fromPosition->$toPosition"
                }

                override fun onChanged(
                    position: Int,
                    count: Int,
                    payload: Any?,
                ) {
                    events += "changed $position+$count"
                }
            },
        )
        return events
    }

    private fun story(
        id: String,
        downloaded: Int = 2,
        tags: MutableList<String>? = null,
    ) = Story(
        id = id,
        title = "Story $id",
        author = "Author",
        sourceUrl = "https://www.royalroad.com/fiction/1/$id",
        totalChapters = 5,
        downloadedChapters = downloaded,
        chapters = chapters(downloaded),
        tags = tags,
    )

    private fun chapters(downloaded: Int): MutableList<Chapter> =
        MutableList(5) { index -> Chapter(id = "c$index", title = "Chapter $index", url = "u$index", downloaded = index < downloaded) }
}
