package com.vinicius741.webnovelarchiver.operations

import com.vinicius741.webnovelarchiver.cleanup.CleanupEngine
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.EpubConfig
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.sync.StorySyncMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StoryWorkTest {
    private val operations = StoryOperations()
    private val story = Story(id = "s1", sourceUrl = "url", sourceId = "provider", tabId = "tab")
    private val seen = mutableListOf<StoryOperationState?>()

    private fun work(
        sync: ManualStorySync = ManualStorySync({ _, _ -> null }, { _, _, _, _ -> story }),
        files: FakeFiles = FakeFiles(),
        epub: EpubGenerator = EpubGenerator { _, _, _, _, _ -> 0 },
    ) = StoryWork(operations, sync, files, epub, io = Dispatchers.Unconfined)

    /** Records each content state the operation slot passes through. */
    private fun record() = { seen += operations.content.value }

    @Test
    fun syncHoldsTheSlotWithItsProgressAndFreesItAfter() =
        runBlocking {
            val sync =
                ManualStorySync({ _, _ -> story }) { url, tab, mode, progress ->
                    assertEquals("url", url)
                    assertEquals("tab", tab)
                    assertEquals(StorySyncMode.Full, mode)
                    seen += operations.content.value
                    progress("Fetching chapters")
                    seen += operations.content.value
                    story
                }

            val result = work(sync = sync).sync(story, StorySyncMode.Full)

            assertEquals("s1", result.story.id)
            assertEquals(listOf("Starting...", "Fetching chapters"), seen.map { it?.message })
            assertTrue(seen.all { it?.kind == StoryOperationKind.SYNC && it.storyId == "s1" })
            assertNull(operations.content.value)
        }

    @Test
    fun aFailedSyncStillFreesTheSlot() =
        runBlocking {
            val sync = ManualStorySync({ _, _ -> null }) { _, _, _, _ -> error("offline") }
            try {
                work(sync = sync).sync(story, StorySyncMode.Default)
                fail("expected the sync to fail")
            } catch (expected: IllegalStateException) {
                assertEquals("offline", expected.message)
            }
            assertNull(operations.content.value)
        }

    @Test
    fun cleanupReportsProgressMarksTheEpubStaleBeforeTheFirstRewriteAndCountsFailures() =
        runBlocking {
            val chapters = listOf(Chapter(id = "a", title = "A"), Chapter(id = "b", title = "B"), Chapter(id = "c", title = "C"))
            val files = FakeFiles(texts = mutableMapOf("a" to "keep", "b" to "drop me"), onEvent = record())

            val stats = work(files = files).cleanup(story, chapters)

            assertEquals(CleanupRunStats(3, 2, 1, 1, 1, 0), stats)
            assertEquals(listOf("mark s1", "write b"), files.events)
            assertEquals(
                listOf("Processing 1/3: A", "Processing 2/3: B", "Processing 3/3: C"),
                files.progress,
            )
            assertEquals(1f, files.lastProgress)
            assertTrue("the stale mark lands while the slot is held", seen.all { it?.kind == StoryOperationKind.CLEANUP })
            assertNull(operations.content.value)
        }

    @Test
    fun cleanupThatChangesNothingLeavesTheEpubCurrent() =
        runBlocking {
            val files = FakeFiles(texts = mutableMapOf("a" to "keep"))

            val stats = work(files = files).cleanup(story, listOf(Chapter(id = "a")))

            assertEquals(0, stats.changedChapters)
            assertTrue(files.events.isEmpty())
        }

    @Test
    fun cleanupOfARemovedStoryAbortsTheRun() =
        runBlocking {
            val files = FakeFiles(texts = mutableMapOf("a" to "drop me", "b" to "drop me"), storyExists = false)
            try {
                work(files = files).cleanup(story, listOf(Chapter(id = "a"), Chapter(id = "b")))
                fail("expected the run to abort")
            } catch (expected: IllegalStateException) {
                assertEquals("Story was removed while cleanup was running", expected.message)
            }
            assertTrue("no chapter is rewritten under a deleted story", files.events.none { it.startsWith("write") })
            assertNull(operations.content.value)
        }

    @Test
    fun cleanupRethrowsCancellationInsteadOfCountingIt() =
        runBlocking {
            val files = FakeFiles(texts = mutableMapOf("a" to "keep"), readFailure = CancellationException("stop"))
            try {
                work(files = files).cleanup(story, listOf(Chapter(id = "a")))
                fail("expected cancellation")
            } catch (expected: CancellationException) {
                assertEquals("stop", expected.message)
            }
            assertNull(operations.content.value)
        }

    @Test
    fun epubProgressTurnsDeterminateFromTheEngineMessages() =
        runBlocking {
            val epub =
                EpubGenerator { _, chapters, _, originals, progress ->
                    assertEquals(listOf(4, 5), originals)
                    seen += operations.content.value
                    progress("Generating EPUB 1/4...")
                    seen += operations.content.value
                    chapters.size
                }

            val files = work(epub = epub).epub(story, listOf(Chapter(id = "a"), Chapter(id = "b")), EpubConfig(), listOf(4, 5))

            assertEquals(2, files)
            assertEquals(listOf("Preparing...", "Generating EPUB 1/4..."), seen.map { it?.message })
            assertEquals(listOf(null, 0.25f), seen.map { it?.progress })
            assertNull(operations.content.value)
        }

    @Test
    fun aLateProgressMessageCannotRestartAFinishedOperation() {
        operations.setContent("s1", StoryOperationKind.EPUB, "Preparing...")
        operations.clearContent("s1", StoryOperationKind.EPUB)

        operations.updateContent("s1", StoryOperationKind.EPUB, "Generating EPUB 2/2...")

        assertNull(operations.content.value)
    }

    @Test
    fun epubProgressParsesOnlyCountedMessages() {
        assertEquals(0.5f, epubProgressFromMessage("Generating EPUB 2/4..."))
        assertNull(epubProgressFromMessage("Preparing..."))
        assertNull(epubProgressFromMessage("Generating EPUB 1/0..."))
    }

    private inner class FakeFiles(
        private val texts: MutableMap<String, String> = mutableMapOf(),
        private val storyExists: Boolean = true,
        private val readFailure: Exception? = null,
        private val onEvent: () -> Unit = {},
    ) : CleanupFiles {
        val events = mutableListOf<String>()
        val progress = mutableListOf<String>()
        var lastProgress: Float? = null

        override fun cleanerFor(story: Story): (String) -> CleanupEngine.CleanupResult =
            { html ->
                val removed = html.contains("drop")
                CleanupEngine.CleanupResult(if (removed) "" else html, sentencesRemoved = if (removed) 1 else 0)
            }

        override suspend fun read(chapter: Chapter): String? {
            readFailure?.let { throw it }
            operations.content.value?.let {
                progress += it.message
                lastProgress = it.progress
            }
            return texts[chapter.id]
        }

        override suspend fun overwrite(
            chapter: Chapter,
            html: String,
        ): Boolean {
            events += "write ${chapter.id}"
            onEvent()
            texts[chapter.id] = html
            return true
        }

        override suspend fun markCleanupApplied(storyId: String): Boolean {
            events += "mark $storyId"
            onEvent()
            return storyExists
        }
    }
}
