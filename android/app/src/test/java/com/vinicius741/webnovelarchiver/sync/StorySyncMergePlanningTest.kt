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
    fun foldKeepsLocalAiDescriptionCarriedByTheSyncedStory() {
        // StorySyncEngine carries the local AI synopsis onto the fresh synced Story; the on-disk
        // record (re-read after the window) carries the same value when nothing changed locally.
        val synced =
            syncedStory(chapters = listOf(chapter("10", downloaded = false)))
                .copy(aiDescription = "local ai synopsis", showAiDescription = true)
        val onDisk =
            syncedStory(
                chapters =
                    listOf(
                        chapter("10", downloaded = true, filePath = "/chapters/10.html"),
                    ),
            ).copy(aiDescription = "local ai synopsis", showAiDescription = true)

        val folded = StorySyncMergePlanning.foldConcurrentChanges(synced, onDisk, RoyalRoadProvider)

        assertEquals("local ai synopsis", folded.aiDescription)
        assertTrue(folded.showAiDescription)
    }

    @Test
    fun foldPreservesAiDescriptionGeneratedDuringSyncWindow() {
        val staleSynced =
            syncedStory(chapters = listOf(chapter("10")))
                .copy(aiDescription = "old synopsis", showAiDescription = false)
        val currentOnDisk =
            syncedStory(chapters = listOf(chapter("10")))
                .copy(aiDescription = "newly generated synopsis", showAiDescription = true)

        val folded = StorySyncMergePlanning.foldConcurrentChanges(staleSynced, currentOnDisk, RoyalRoadProvider)

        assertEquals("newly generated synopsis", folded.aiDescription)
        assertTrue(folded.showAiDescription)
    }

    @Test
    fun foldKeepsExplicitAiDescriptionResetMadeDuringSyncWindow() {
        // The stale pre-window snapshot still carries the AI synopsis, but the user deleted it
        // while the sync was in flight: null must win, not the stale value (R04).
        val staleSynced =
            syncedStory(chapters = listOf(chapter("10")))
                .copy(aiDescription = "old synopsis", showAiDescription = true)
        val currentOnDisk = syncedStory(chapters = listOf(chapter("10")))

        val folded = StorySyncMergePlanning.foldConcurrentChanges(staleSynced, currentOnDisk, RoyalRoadProvider)

        assertNull(folded.aiDescription)
    }

    @Test
    fun foldKeepsAiCoverCarriedByTheSyncedStory() {
        // StorySyncEngine carries the local AI cover onto the fresh synced Story; the on-disk
        // record carries the same path when nothing changed locally during the window.
        val synced =
            syncedStory(chapters = listOf(chapter("10", downloaded = false)))
                .copy(aiCoverPath = "covers/s.png", showAiCover = true)
        val onDisk =
            syncedStory(
                chapters =
                    listOf(
                        chapter("10", downloaded = true, filePath = "/chapters/10.html"),
                    ),
            ).copy(aiCoverPath = "covers/s.png", showAiCover = true)

        val folded = StorySyncMergePlanning.foldConcurrentChanges(synced, onDisk, RoyalRoadProvider)

        assertEquals("covers/s.png", folded.aiCoverPath)
        assertTrue(folded.showAiCover)
    }

    @Test
    fun foldKeepsExplicitCoverDeletionMadeDuringSyncWindow() {
        val staleSynced =
            syncedStory(chapters = listOf(chapter("10")))
                .copy(aiCoverPath = "covers/s.png", showAiCover = true)
        val currentOnDisk = syncedStory(chapters = listOf(chapter("10")))

        val folded = StorySyncMergePlanning.foldConcurrentChanges(staleSynced, currentOnDisk, RoyalRoadProvider)

        assertNull(folded.aiCoverPath)
    }

    @Test
    fun foldPreservesAiCoverAppliedOrToggledDuringSyncWindow() {
        val staleSynced =
            syncedStory(chapters = listOf(chapter("10")))
                .copy(aiCoverPath = null, showAiCover = false)
        val currentOnDisk =
            syncedStory(chapters = listOf(chapter("10")))
                .copy(aiCoverPath = "covers/s.png", showAiCover = false)

        val folded = StorySyncMergePlanning.foldConcurrentChanges(staleSynced, currentOnDisk, RoyalRoadProvider)

        assertEquals("covers/s.png", folded.aiCoverPath)
        assertEquals(false, folded.showAiCover)
    }

    @Test
    fun foldKeepsAiContextChaptersCarriedByTheSyncedStory() {
        // StorySyncEngine carries the local context-chapter selection onto the fresh synced Story;
        // the on-disk record carries the same selection when nothing changed locally.
        val synced =
            syncedStory(chapters = listOf(chapter("10", downloaded = false)))
                .copy(aiContextChapterIndices = mutableListOf(0, 2))
        val onDisk =
            syncedStory(
                chapters =
                    listOf(
                        chapter("10", downloaded = true, filePath = "/chapters/10.html"),
                    ),
            ).copy(aiContextChapterIndices = mutableListOf(0, 2))

        val folded = StorySyncMergePlanning.foldConcurrentChanges(synced, onDisk, RoyalRoadProvider)

        assertEquals(listOf(0, 2), folded.aiContextChapterIndices)
    }

    @Test
    fun foldKeepsExplicitContextSelectionResetMadeDuringSyncWindow() {
        val staleSynced =
            syncedStory(chapters = listOf(chapter("10")))
                .copy(aiContextChapterIndices = mutableListOf(0, 2))
        val currentOnDisk = syncedStory(chapters = listOf(chapter("10")))

        val folded = StorySyncMergePlanning.foldConcurrentChanges(staleSynced, currentOnDisk, RoyalRoadProvider)

        assertNull(folded.aiContextChapterIndices)
    }

    @Test
    fun foldKeepsExplicitStrengthResetMadeDuringSyncWindow() {
        val staleSynced =
            syncedStory(chapters = listOf(chapter("10")))
                .copy(chapterRewriteStrength = "balanced")
        val currentOnDisk = syncedStory(chapters = listOf(chapter("10")))

        val folded = StorySyncMergePlanning.foldConcurrentChanges(staleSynced, currentOnDisk, RoyalRoadProvider)

        assertNull(folded.chapterRewriteStrength)
    }

    @Test
    fun foldKeepsTabMoveMadeDuringSyncWindow() {
        val staleSynced = syncedStory(chapters = listOf(chapter("10"))).copy(tabId = "reading")
        val currentOnDisk = syncedStory(chapters = listOf(chapter("10"))).copy(tabId = "wishlist")

        val folded = StorySyncMergePlanning.foldConcurrentChanges(staleSynced, currentOnDisk, RoyalRoadProvider)

        assertEquals("wishlist", folded.tabId)
    }

    @Test
    fun foldKeepsEpubGenerationCompletedDuringSyncWindow() {
        val staleSynced = syncedStory(chapters = listOf(chapter("10")))
        val currentOnDisk =
            syncedStory(chapters = listOf(chapter("10")))
                .copy(epubPath = "epubs/s/out.epub", epubStale = false)

        val folded = StorySyncMergePlanning.foldConcurrentChanges(staleSynced, currentOnDisk, RoyalRoadProvider)

        assertEquals("epubs/s/out.epub", folded.epubPath)
        assertEquals(false, folded.epubStale)
    }

    @Test
    fun foldPreservesAiContextChaptersPickedDuringSyncWindow() {
        val staleSynced = syncedStory(chapters = listOf(chapter("10")))
        val currentOnDisk =
            syncedStory(chapters = listOf(chapter("10")))
                .copy(aiContextChapterIndices = mutableListOf(1, 3))

        val folded = StorySyncMergePlanning.foldConcurrentChanges(staleSynced, currentOnDisk, RoyalRoadProvider)

        assertEquals(listOf(1, 3), folded.aiContextChapterIndices)
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
