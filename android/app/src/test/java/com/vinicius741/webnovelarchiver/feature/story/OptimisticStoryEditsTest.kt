package com.vinicius741.webnovelarchiver.feature.story

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.Story
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OptimisticStoryEditsTest {
    private val dispatcher = StandardTestDispatcher()
    private val story = Story(id = "s1", chapters = mutableListOf(Chapter(id = "c1"), Chapter(id = "c2"), Chapter(id = "c3")))
    private var changes = 0
    private val edits = OptimisticStoryEdits { changes += 1 }

    @Test
    fun anEditShowsBeforeItsWriteEnds() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Boolean>()
            launch { edits.commit(StoryEdit.Bookmark("c2")) { gate.await() } }
            runCurrent()

            assertEquals("c2", edits.shown(story)?.lastReadChapterId)
            assertSame("stable while nothing changes", edits.shown(story), edits.shown(story))
            assertEquals(1, changes)

            gate.complete(true)
            runCurrent()

            assertSame("the published story shows again once written", story, edits.shown(story))
            assertEquals(2, changes)
        }

    @Test
    fun writesRunInTapOrder() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            val written = mutableListOf<String>()
            launch {
                edits.commit(StoryEdit.Bookmark("c2")) {
                    gate.await()
                    written += "c2"
                    true
                }
            }
            launch {
                edits.commit(StoryEdit.Bookmark("c3")) {
                    written += "c3"
                    true
                }
            }
            runCurrent()
            assertEquals("c3", edits.shown(story)?.lastReadChapterId)
            assertTrue(written.isEmpty())

            gate.complete(Unit)
            runCurrent()
            assertEquals(listOf("c2", "c3"), written)
        }

    @Test
    fun aRejectedOrFailedWriteRollsBack() =
        runTest(dispatcher) {
            var rejected: Boolean? = null
            var failed: Boolean? = null
            launch { rejected = edits.commit(StoryEdit.Bookmark("c2")) { false } }
            launch { failed = edits.commit(StoryEdit.Bookmark("c3")) { error("disk full") } }
            runCurrent()

            assertEquals(false, rejected)
            assertEquals(false, failed)
            assertSame(story, edits.shown(story))
            assertFalse(edits.save { error("disk full") })
            assertTrue(edits.save {})
        }

    @Test
    fun closingTheScreenDoesNotDropAWriteTheUserSaw() =
        runTest(dispatcher) {
            val screen = CoroutineScope(SupervisorJob() + dispatcher)
            val gate = CompletableDeferred<Unit>()
            var written = false
            screen.launch { edits.commit(StoryEdit.Bookmark("c1")) { gate.await().let { true } } }
            screen.launch {
                edits.commit(StoryEdit.Bookmark("c2")) {
                    written = true
                    true
                }
            }
            runCurrent()

            screen.cancel()
            gate.complete(Unit)
            runCurrent()

            assertTrue(written)
            assertSame(story, edits.shown(story))
        }
}
