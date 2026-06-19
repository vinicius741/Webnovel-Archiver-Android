package com.vinicius741.webnovelarchiver.sync

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.ChapterInfo
import com.vinicius741.webnovelarchiver.domain.model.EpubConfig
import com.vinicius741.webnovelarchiver.domain.model.NovelMetadata
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.source.NetworkClient
import com.vinicius741.webnovelarchiver.source.RoyalRoadProvider
import com.vinicius741.webnovelarchiver.source.SourceProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StorySyncPlanningTest {
    @Test
    fun mergeChaptersPreservesDownloadedChapterAcrossSlugChangeAndRemapsBookmark() {
        val existing =
            listOf(
                Chapter(
                    id = "old-id",
                    title = "Old title",
                    url = "https://www.royalroad.com/fiction/1/story/chapter/10/old-slug",
                    filePath = "/chapters/10.html",
                    downloaded = true,
                ),
            )
        val incoming =
            listOf(
                ChapterInfo(
                    id = "10",
                    title = "New title (2 hours ago)",
                    url = "https://www.royalroad.com/fiction/1/story/chapter/10/new-slug",
                ),
            )

        val merge = StorySyncPlanning.mergeChapters(existing, incoming, RoyalRoadProvider, lastRead = "old-id")

        assertEquals(emptyList<String>(), merge.newChapterIds)
        assertEquals(emptyList<Chapter>(), merge.removedChapters)
        assertEquals("10", merge.lastReadChapterId)
        assertEquals("10", merge.chapters.single().id)
        assertEquals("New title", merge.chapters.single().title)
        assertEquals("/chapters/10.html", merge.chapters.single().filePath)
        assertTrue(merge.chapters.single().downloaded)
    }

    @Test
    fun mergeChaptersReportsRemovedChaptersAndClearsMissingBookmark() {
        val existing =
            listOf(
                Chapter(id = "c1", title = "One", url = "https://example.com/one", downloaded = true),
                Chapter(id = "c2", title = "Two", url = "https://example.com/two", downloaded = false),
            )
        val incoming = listOf(ChapterInfo(id = "c1", title = "One", url = "https://example.com/one"))

        val merge = StorySyncPlanning.mergeChapters(existing, incoming, noIdProvider(), lastRead = "c2")

        assertEquals(listOf("c2"), merge.removedChapters.map { it.id })
        assertNull(merge.lastReadChapterId)
    }

    @Test
    fun pendingNewChapterIdsMergeExistingAndNewUndownloadedIds() {
        val chapters =
            mutableListOf(
                Chapter(id = "c1", downloaded = true),
                Chapter(id = "c2", downloaded = false),
                Chapter(id = "c3", downloaded = false),
                Chapter(id = "c4", downloaded = true),
            )

        val pending =
            StorySyncPlanning.buildPendingNewChapterIds(
                existingPending = listOf("c2", "missing"),
                chapterIdsToAdd = listOf("c3", "c4"),
                mergedChapters = chapters,
            )

        assertEquals(listOf("c2", "c3"), pending)
    }

    @Test
    fun pendingNewChapterIdsReturnNullWhenNoUndownloadedPendingRemain() {
        val pending =
            StorySyncPlanning.buildPendingNewChapterIds(
                existingPending = listOf("c1"),
                chapterIdsToAdd = listOf("c2"),
                mergedChapters =
                    listOf(
                        Chapter(id = "c1", downloaded = true),
                        Chapter(id = "c2", downloaded = true),
                    ),
            )

        assertNull(pending)
    }

    @Test
    fun updateEpubConfigExtendsRangeEndWhenExistingRangeReachedOldEnd() {
        val existing =
            storyWithChapters(4).apply {
                epubConfig = EpubConfig(maxChaptersPerEpub = 150, rangeStart = 2, rangeEnd = 4)
            }

        val updated = StorySyncPlanning.updateEpubConfigForSync(existing, nextChapterCount = 6)

        assertEquals(EpubConfig(maxChaptersPerEpub = 150, rangeStart = 2, rangeEnd = 6), updated)
    }

    @Test
    fun updateEpubConfigKeepsExplicitRangeEndWhenItWasNotAtOldEnd() {
        val existing =
            storyWithChapters(6).apply {
                epubConfig = EpubConfig(maxChaptersPerEpub = 150, rangeStart = 2, rangeEnd = 4)
            }

        val updated = StorySyncPlanning.updateEpubConfigForSync(existing, nextChapterCount = 8)

        assertEquals(EpubConfig(maxChaptersPerEpub = 150, rangeStart = 2, rangeEnd = 4), updated)
    }

    @Test
    fun shouldMarkEpubStaleOnlyWhenChapterCountChangedAndStoryHasEpub() {
        val existing =
            storyWithChapters(4).apply {
                epubPath = "/tmp/story.epub"
            }

        assertTrue(StorySyncPlanning.shouldMarkEpubStale(existing, nextChapterCount = 5))
        assertFalse(StorySyncPlanning.shouldMarkEpubStale(existing, nextChapterCount = 4))
        assertFalse(StorySyncPlanning.shouldMarkEpubStale(storyWithChapters(4), nextChapterCount = 5))
    }

    private fun storyWithChapters(count: Int): Story =
        Story(
            id = "story",
            title = "Story",
            author = "Author",
            chapters =
                (1..count)
                    .map { index ->
                        Chapter(id = "c$index", title = "Chapter $index", url = "https://example.com/$index")
                    }.toMutableList(),
        )

    private fun noIdProvider(): SourceProvider =
        object : SourceProvider {
            override val name = "No ID"
            override val baseUrl = "https://example.com"

            override fun isSource(url: String) = true

            override fun getStoryId(url: String) = "story"

            override fun getChapterId(url: String): String? = null

            override fun parseMetadata(html: String) = NovelMetadata("Story", "Author")

            override suspend fun getChapterList(
                html: String,
                url: String,
                network: NetworkClient,
                progress: (String) -> Unit,
            ): List<ChapterInfo> = emptyList()

            override fun parseChapterContent(html: String) = html
        }
}
