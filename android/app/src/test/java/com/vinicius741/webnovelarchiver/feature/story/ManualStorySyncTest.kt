package com.vinicius741.webnovelarchiver.feature.story

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.sync.StorySyncMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class ManualStorySyncTest {
    @Test
    fun lookupPrecedesFetchAndOnlyNewChaptersArePlanned() =
        runBlocking {
            val calls = mutableListOf<String>()
            val before = Story(pendingNewChapterIds = mutableListOf("old"))
            val synced =
                Story(
                    chapters = mutableListOf(Chapter(id = "old"), Chapter(id = "new")),
                    pendingNewChapterIds = mutableListOf("old", "new"),
                )
            var stored = before
            val sync =
                ManualStorySync(
                    lookup = { sourceId, url ->
                        assertEquals("provider", sourceId)
                        assertEquals("url", url)
                        calls += "lookup"
                        stored
                    },
                    fetch = { url, tab, mode, progress ->
                        assertEquals("url", url)
                        assertEquals("tab", tab)
                        assertEquals(StorySyncMode.Full, mode)
                        calls += "fetch"
                        stored = synced
                        progress("Fetching")
                        synced
                    },
                )
            val result = sync.run("url", "tab", StorySyncMode.Full, "provider") { calls += it }
            assertEquals(listOf("lookup", "fetch", "Fetching"), calls)
            assertSame(synced, result.story)
            assertEquals(listOf("new"), result.downloads.chapterIds)
        }

    @Test
    fun missingPriorStoryAndUnchangedBacklogDoNotQueue() =
        runBlocking {
            val synced = Story(chapters = mutableListOf(Chapter(id = "old")), pendingNewChapterIds = mutableListOf("old"))
            for (before in listOf(null, synced)) {
                val sync = ManualStorySync({ _, _ -> before }, { _, _, _, _ -> synced })
                assertEquals(SyncDownloadAction.NONE, sync.run("url", null, StorySyncMode.Default) {}.downloads.action)
            }
        }

    @Test
    fun errorsAndCancellationPropagateWithoutReturningAPlan() =
        runBlocking {
            for (failure in listOf(IllegalStateException("fetch failed"), CancellationException("cancelled"))) {
                val sync = ManualStorySync({ _, _ -> null }, { _, _, _, _ -> throw failure })
                try {
                    sync.run("url", null, StorySyncMode.Default) {}
                    fail("Must not complete")
                } catch (actual: Exception) {
                    assertEquals(failure.javaClass, actual.javaClass)
                    assertEquals(failure.message, actual.message)
                }
            }
        }
}
