package com.vinicius741.webnovelarchiver.download

import com.vinicius741.webnovelarchiver.domain.model.SourceDownloadSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadRequestPacerTest {
    @Test
    fun firstRequestForEachSourceStartsImmediately() =
        runBlocking {
            val sleeps = mutableListOf<Long>()
            val pacer =
                DownloadRequestPacer(
                    nowMillis = { 1_000L },
                    sleep = { sleeps += it },
                )
            val settings = SourceDownloadSettings(delay = 30_000L, delayMax = 30_000L)

            pacer.awaitTurn("Scribble Hub", "story-a", "job-a", "Chapter 1") { settings }
            pacer.awaitTurn("Royal Road", "story-b", "job-b", "Chapter 1") { settings }

            assertTrue(sleeps.isEmpty())
            assertTrue(pacer.snapshots.value.isEmpty())
        }

    @Test
    fun subsequentRequestsAreSpacedAndWaitingStateUsesTheReservedTime() =
        runBlocking {
            var now = 1_000L
            val sleeps = mutableListOf<Long>()
            lateinit var pacer: DownloadRequestPacer
            pacer =
                DownloadRequestPacer(
                    nowMillis = { now },
                    sleep = { millis ->
                        val snapshot = pacer.snapshots.value.getValue("Scribble Hub")
                        assertEquals("story-a", snapshot.storyId)
                        assertEquals("job-2", snapshot.jobId)
                        assertEquals("Chapter 2", snapshot.chapterTitle)
                        assertEquals(3_500L, snapshot.nextRequestAtMillis)
                        sleeps += millis
                        now += millis
                    },
                    randomBetween = { minimum, _ -> minimum },
                )
            val settings = SourceDownloadSettings(delay = 2_500L, delayMax = 4_000L)

            pacer.awaitTurn("Scribble Hub", "story-a", "job-1", "Chapter 1") { settings }
            pacer.awaitTurn("Scribble Hub", "story-a", "job-2", "Chapter 2") { settings }

            assertEquals(listOf(1_000L, 1_000L, 500L), sleeps)
            assertEquals(3_500L, now)
            assertTrue(pacer.snapshots.value.isEmpty())
        }

    @Test
    fun reducingDelayReleasesAnExistingWaitPromptly() =
        runBlocking {
            var now = 0L
            var settings = SourceDownloadSettings(delay = 30_000L, delayMax = 30_000L)
            val sleeps = mutableListOf<Long>()
            val pacer =
                DownloadRequestPacer(
                    nowMillis = { now },
                    sleep = { millis ->
                        sleeps += millis
                        now += millis
                        settings = SourceDownloadSettings(delay = 0L, delayMax = 0L)
                    },
                )

            pacer.awaitTurn("Scribble Hub", "story-a", "job-1", "Chapter 1") { settings }
            pacer.awaitTurn("Scribble Hub", "story-a", "job-2", "Chapter 2") { settings }

            assertEquals(listOf(1_000L), sleeps)
            assertEquals(1_000L, now)
            assertTrue(pacer.snapshots.value.isEmpty())
        }

    @Test
    fun sourcePermissionWaitDefinesTheActualStartForTheNextDelay() =
        runBlocking {
            var now = 0L
            val sleeps = mutableListOf<Long>()
            val pacer =
                DownloadRequestPacer(
                    nowMillis = { now },
                    sleep = { millis ->
                        sleeps += millis
                        now += millis
                    },
                    randomBetween = { minimum, _ -> minimum },
                )
            val settings = SourceDownloadSettings(delay = 2_000L, delayMax = 2_000L)

            pacer.awaitTurn(
                providerName = "Scribble Hub",
                storyId = "story-a",
                jobId = "job-1",
                chapterTitle = "Chapter 1",
                claimSourcePermission = { now += 5_000L },
            ) {
                settings
            }
            pacer.awaitTurn(
                providerName = "Scribble Hub",
                storyId = "story-a",
                jobId = "job-2",
                chapterTitle = "Chapter 2",
            ) {
                settings
            }

            assertEquals(listOf(1_000L, 1_000L), sleeps)
            assertEquals(7_000L, now)
        }

    @Test
    fun inactiveFirstRequestDoesNotClaimSourcePermission() =
        runBlocking {
            var claimed = false
            val pacer = DownloadRequestPacer(nowMillis = { 1_000L })

            val error =
                runCatching {
                    pacer.awaitTurn(
                        providerName = "Scribble Hub",
                        storyId = "story-a",
                        jobId = "job-1",
                        chapterTitle = "Chapter 1",
                        claimSourcePermission = { claimed = true },
                    ) {
                        throw DownloadJobInactiveException("job-1")
                    }
                }.exceptionOrNull()

            assertTrue(error is DownloadJobInactiveException)
            assertFalse(claimed)
            assertTrue(pacer.snapshots.value.isEmpty())
        }

    @Test
    fun cancellationClearsTheWaitingSnapshot() =
        runTest {
            val pacer =
                DownloadRequestPacer(
                    nowMillis = { 0L },
                    sleep = { awaitCancellation() },
                )
            val settings = SourceDownloadSettings(delay = 30_000L, delayMax = 30_000L)
            pacer.awaitTurn("Scribble Hub", "story-a", "job-1", "Chapter 1") { settings }

            val waiting =
                launch {
                    pacer.awaitTurn("Scribble Hub", "story-a", "job-2", "Chapter 2") { settings }
                }
            runCurrent()
            assertEquals(
                "job-2",
                pacer.snapshots.value
                    .getValue("Scribble Hub")
                    .jobId,
            )

            waiting.cancelAndJoin()

            assertTrue(pacer.snapshots.value.isEmpty())
        }

    @Test
    fun waitsForDifferentSourcesDoNotBlockEachOther() =
        runTest {
            val pacer =
                DownloadRequestPacer(
                    nowMillis = { testScheduler.currentTime },
                    sleep = { delay(it) },
                    randomBetween = { minimum, _ -> minimum },
                )
            val settings = SourceDownloadSettings(delay = 2_000L, delayMax = 2_000L)
            pacer.awaitTurn("Scribble Hub", "story-a", "job-a1", "Chapter 1") { settings }
            pacer.awaitTurn("Royal Road", "story-b", "job-b1", "Chapter 1") { settings }
            val completions = mutableListOf<String>()

            launch {
                pacer.awaitTurn("Scribble Hub", "story-a", "job-a2", "Chapter 2") { settings }
                completions += "Scribble Hub"
            }
            launch {
                pacer.awaitTurn("Royal Road", "story-b", "job-b2", "Chapter 2") { settings }
                completions += "Royal Road"
            }
            runCurrent()

            assertEquals(setOf("Scribble Hub", "Royal Road"), pacer.snapshots.value.keys)
            advanceTimeBy(2_000L)
            runCurrent()

            assertEquals(setOf("Scribble Hub", "Royal Road"), completions.toSet())
            assertTrue(pacer.snapshots.value.isEmpty())
        }

    @Test
    fun concurrentRequestsForOneSourceClaimSequentialSlots() =
        runTest {
            val pacer =
                DownloadRequestPacer(
                    nowMillis = { testScheduler.currentTime },
                    sleep = { delay(it) },
                    randomBetween = { minimum, _ -> minimum },
                )
            val settings = SourceDownloadSettings(delay = 1_000L, delayMax = 1_000L)
            pacer.awaitTurn("Scribble Hub", "story-a", "job-1", "Chapter 1") { settings }
            val completions = mutableListOf<String>()

            launch {
                pacer.awaitTurn("Scribble Hub", "story-a", "job-2", "Chapter 2") { settings }
                completions += "job-2"
            }
            launch {
                pacer.awaitTurn("Scribble Hub", "story-a", "job-3", "Chapter 3") { settings }
                completions += "job-3"
            }
            runCurrent()
            assertEquals(
                "job-2",
                pacer.snapshots.value
                    .getValue("Scribble Hub")
                    .jobId,
            )

            advanceTimeBy(1_000L)
            runCurrent()
            assertEquals(listOf("job-2"), completions)
            assertEquals(
                "job-3",
                pacer.snapshots.value
                    .getValue("Scribble Hub")
                    .jobId,
            )

            advanceTimeBy(1_000L)
            runCurrent()
            assertEquals(listOf("job-2", "job-3"), completions)
            assertTrue(pacer.snapshots.value.isEmpty())
        }

    @Test
    fun invalidRangesAreNormalizedBeforeRandomSelection() =
        runBlocking {
            var now = 100L
            val ranges = mutableListOf<Pair<Long, Long>>()
            val pacer =
                DownloadRequestPacer(
                    nowMillis = { now },
                    sleep = { now += it },
                    randomBetween = { minimum, maximum ->
                        ranges += minimum to maximum
                        maximum
                    },
                )
            val settings = SourceDownloadSettings(delay = 500L, delayMax = -1L)

            pacer.awaitTurn("Royal Road", "story-a", "job-1", "Chapter 1") { settings }
            pacer.awaitTurn("Royal Road", "story-a", "job-2", "Chapter 2") { settings }

            assertEquals(listOf(500L to 500L), ranges)
            assertEquals(600L, now)
        }
}
