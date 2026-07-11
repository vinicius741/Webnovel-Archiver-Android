package com.vinicius741.webnovelarchiver.sync

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.DownloadStatus
import com.vinicius741.webnovelarchiver.domain.model.NovelMetadata
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.source.RoyalRoadProvider
import com.vinicius741.webnovelarchiver.source.SourceProvider
import com.vinicius741.webnovelarchiver.source.network.NetworkClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StorySyncMergePlanningTest {
    @Test
    fun foldReturnsSyncedStoryUnchangedWhenNoOnDiskStory() {
        val synced = syncedStory(chapters = listOf(chapter("10", downloaded = false)))

        val folded = StorySyncMergePlanning.foldConcurrentChanges(synced, onDisk = null, RoyalRoadProvider)

        assertSame(synced, folded)
    }

    @Test
    fun foldPreservesDownloadThatCompletedDuringSyncWindow() {
        // The synced story was built from a stale pre-sync snapshot where chapter 10 was not yet
        // downloaded; meanwhile a download completed on disk, setting downloaded + filePath.
        val synced = syncedStory(chapters = listOf(chapter("10", downloaded = false)))
        val onDisk =
            syncedStory(
                chapters =
                    listOf(
                        chapter("10", downloaded = true, filePath = "/chapters/10.html", content = "<p>html</p>")
                            .copy(downloadedAt = 1_700_000_000_000L),
                    ),
            )

        val folded = StorySyncMergePlanning.foldConcurrentChanges(synced, onDisk, RoyalRoadProvider)

        val merged = folded.chapters.single()
        assertTrue("downloaded state must survive the sync write", merged.downloaded)
        assertEquals("/chapters/10.html", merged.filePath)
        assertEquals("<p>html</p>", merged.content)
        assertEquals(1_700_000_000_000L, merged.downloadedAt)
        assertEquals(1, folded.downloadedChapters)
        assertEquals(DownloadStatus.completed, folded.status)
    }

    @Test
    fun foldRecomputesPartialStatusWhenOnlySomeChaptersDownloaded() {
        val synced =
            syncedStory(
                chapters =
                    listOf(
                        chapter("10", downloaded = false),
                        chapter("20", downloaded = false),
                    ),
            )
        val onDisk =
            syncedStory(
                chapters =
                    listOf(
                        chapter("10", downloaded = true, filePath = "/chapters/10.html"),
                        chapter("20", downloaded = false),
                    ),
            )

        val folded = StorySyncMergePlanning.foldConcurrentChanges(synced, onDisk, RoyalRoadProvider)

        assertEquals(1, folded.downloadedChapters)
        assertEquals(DownloadStatus.partial, folded.status)
    }

    @Test
    fun foldKeepsSyncedMetadataAsSourceOfTruth() {
        val synced =
            syncedStory(
                title = "Synced Title",
                chapters = listOf(chapter("10", downloaded = false, title = "Synced Chapter Title")),
            )
        val onDisk =
            syncedStory(
                title = "Stale On-Disk Title",
                chapters = listOf(chapter("10", downloaded = true, filePath = "/c.html", title = "Stale Chapter Title")),
            )

        val folded = StorySyncMergePlanning.foldConcurrentChanges(synced, onDisk, RoyalRoadProvider)

        assertEquals("Synced Title", folded.title)
        assertEquals("Synced Chapter Title", folded.chapters.single().title)
    }

    @Test
    fun foldPreservesLatestOnDiskReadingPosition() {
        val synced = syncedStory(chapters = listOf(chapter("10"))).copy(lastReadChapterId = null)
        val onDisk = syncedStory(chapters = listOf(chapter("10"))).copy(lastReadChapterId = "10")

        val folded = StorySyncMergePlanning.foldConcurrentChanges(synced, onDisk, RoyalRoadProvider)

        assertEquals("10", folded.lastReadChapterId)
    }

    @Test
    fun foldPrefersBookmarkChangedDuringSyncWindow() {
        val synced =
            syncedStory(
                chapters =
                    listOf(
                        chapter("10"),
                        chapter("20"),
                    ),
            ).copy(lastReadChapterId = "10")
        val onDisk =
            syncedStory(
                chapters =
                    listOf(
                        chapter("10"),
                        chapter("20"),
                    ),
            ).copy(lastReadChapterId = "20")

        val folded = StorySyncMergePlanning.foldConcurrentChanges(synced, onDisk, RoyalRoadProvider)

        assertEquals("20", folded.lastReadChapterId)
    }

    @Test
    fun foldPreservesBookmarkClearedDuringSyncWindow() {
        val synced = syncedStory(chapters = listOf(chapter("10"))).copy(lastReadChapterId = "10")
        val onDisk = syncedStory(chapters = listOf(chapter("10"))).copy(lastReadChapterId = null)

        val folded = StorySyncMergePlanning.foldConcurrentChanges(synced, onDisk, RoyalRoadProvider)

        assertNull(folded.lastReadChapterId)
    }

    @Test
    fun foldHandlesIdMismatchByReturningSyncedStory() {
        val synced = syncedStory().copy(id = "story-a")
        val onDisk = syncedStory().copy(id = "story-b")

        val folded = StorySyncMergePlanning.foldConcurrentChanges(synced, onDisk, RoyalRoadProvider)

        assertSame(synced, folded)
    }

    @Test
    fun foldMatchesChaptersByStableIdAcrossUrlSlugChanges() {
        // Synced story carries the new slug; on-disk story carries the old slug but a stable chapter
        // id derivable from the URL by the provider. The download state must still be preserved.
        val synced =
            syncedStory(
                chapters =
                    listOf(
                        Chapter(
                            id = "10",
                            title = "New",
                            url = "https://www.royalroad.com/fiction/1/story/chapter/10/new-slug",
                            downloaded = false,
                        ),
                    ),
            )
        val onDisk =
            syncedStory(
                chapters =
                    listOf(
                        Chapter(
                            id = "10",
                            title = "Old",
                            url = "https://www.royalroad.com/fiction/1/story/chapter/10/old-slug",
                            downloaded = true,
                            filePath = "/chapters/10.html",
                        ),
                    ),
            )

        val folded = StorySyncMergePlanning.foldConcurrentChanges(synced, onDisk, RoyalRoadProvider)

        assertTrue(folded.chapters.single().downloaded)
        assertEquals("/chapters/10.html", folded.chapters.single().filePath)
    }

    @Test
    fun foldRemapsOnDiskBookmarkAcrossUrlSlugChanges() {
        val synced =
            syncedStory(
                chapters =
                    listOf(
                        Chapter(
                            id = "10",
                            title = "New",
                            url = "https://www.royalroad.com/fiction/1/story/chapter/10/new-slug",
                        ),
                    ),
            ).copy(lastReadChapterId = null)
        val onDisk =
            syncedStory(
                chapters =
                    listOf(
                        Chapter(
                            id = "old-10",
                            title = "Old",
                            url = "https://www.royalroad.com/fiction/1/story/chapter/10/old-slug",
                        ),
                    ),
            ).copy(lastReadChapterId = "old-10")

        val folded = StorySyncMergePlanning.foldConcurrentChanges(synced, onDisk, RoyalRoadProvider)

        assertEquals("10", folded.lastReadChapterId)
    }

    @Test
    fun foldMatchesChaptersByIdWhenProviderReturnsNullChapterId() {
        val provider = noIdProvider()
        val synced = syncedStory(chapters = listOf(Chapter(id = "c1", title = "One", url = "https://example.com/one", downloaded = false)))
        val onDisk =
            syncedStory(
                chapters =
                    listOf(
                        Chapter(id = "c1", title = "One", url = "https://example.com/one", downloaded = true, filePath = "/c1.html"),
                    ),
            )

        val folded = StorySyncMergePlanning.foldConcurrentChanges(synced, onDisk, provider)

        assertTrue(folded.chapters.single().downloaded)
    }

    private fun syncedStory(
        title: String = "Synced",
        chapters: List<Chapter> = emptyList(),
    ): Story =
        Story(
            id = "story",
            title = title,
            author = "Author",
            sourceUrl = "https://www.royalroad.com/fiction/1/story",
            chapters = chapters.toMutableList(),
        )

    private fun chapter(
        stableId: String,
        downloaded: Boolean = false,
        filePath: String? = null,
        content: String? = null,
        title: String = "Chapter $stableId",
    ): Chapter =
        Chapter(
            id = stableId,
            title = title,
            url = "https://www.royalroad.com/fiction/1/story/chapter/$stableId/slug",
            downloaded = downloaded,
            filePath = filePath,
            content = content,
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
            ): List<com.vinicius741.webnovelarchiver.domain.model.ChapterInfo> = emptyList()

            override fun parseChapterContent(html: String) = html
        }
}
