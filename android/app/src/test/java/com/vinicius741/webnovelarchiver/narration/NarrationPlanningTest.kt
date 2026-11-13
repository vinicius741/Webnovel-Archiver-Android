package com.vinicius741.webnovelarchiver.narration

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NarrationPlanningTest {
    private val bella = NarrationVoice("builtin:kokoro-en-v0_19", "kokoro-en-v0_19:af_bella", 1f, "Bella")
    private val george = NarrationVoice("builtin:kokoro-en-v0_19", "kokoro-en-v0_19:bm_george", 1f, "George")
    private var ids = 0

    private fun enqueue(
        jobs: List<NarrationJob>,
        vararg requests: NarrationRequest,
        packs: Set<String> = emptySet(),
        front: Boolean = false,
    ) = NarrationPlanning.enqueue(jobs, requests.toList(), { _, chapter -> chapter in packs }, front, 0L) { "job${ids++}" }

    private fun request(
        chapter: String,
        voice: NarrationVoice = bella,
        origin: NarrationOrigin = NarrationOrigin.MANUAL,
    ) = NarrationRequest("story", chapter, voice, origin)

    @Test
    fun folderNamesNeverCollideForIdsThatSanitizeAlike() {
        val long = "x".repeat(200)
        assertNotEquals(NarrationPlanning.folderName("a/b"), NarrationPlanning.folderName("a_b"))
        assertNotEquals(NarrationPlanning.folderName(long + "1"), NarrationPlanning.folderName(long + "2"))
        assertTrue(NarrationPlanning.folderName("https://example.com/c?id=1").matches(Regex("[0-9a-f]{32}")))
    }

    @Test
    fun fingerprintChangesWithTextOrChunking() {
        val base = NarrationPlanning.fingerprint(listOf("One.", "Two."), 2)
        assertEquals(base, NarrationPlanning.fingerprint(listOf("One.", "Two."), 2))
        assertNotEquals(base, NarrationPlanning.fingerprint(listOf("One.", "Two!"), 2))
        assertNotEquals(base, NarrationPlanning.fingerprint(listOf("One.", "Two."), 3))
        assertNotEquals(base, NarrationPlanning.fingerprint(listOf("One. Two."), 2))
    }

    @Test
    fun enqueueSkipsChaptersWithAudioUnlessRegenerating() {
        val jobs = enqueue(emptyList(), request("c1"), request("c2"), packs = setOf("c1"))
        assertEquals(listOf("c2"), jobs.map { it.chapterId })
        val regenerated = enqueue(jobs, request("c1", george, NarrationOrigin.REGENERATE), packs = setOf("c1"))
        assertEquals(listOf("c2", "c1"), regenerated.map { it.chapterId })
    }

    @Test
    fun keepAheadNeverChangesAnExistingJob() {
        val manual = enqueue(emptyList(), request("c1", george, NarrationOrigin.REGENERATE))
        val after = enqueue(manual, request("c1", bella, NarrationOrigin.KEEP_AHEAD))
        assertEquals(george, after.single().voice)
        assertEquals(NarrationOrigin.REGENERATE, after.single().origin)
    }

    @Test
    fun aUserRequestReplacesTheJobsVoiceAndClearsAFailure() {
        val failed = enqueue(emptyList(), request("c1")).map { it.copy(state = NarrationJobState.FAILED, error = "boom") }
        val after = enqueue(failed, request("c1", george))
        assertEquals(george, after.single().voice)
        assertEquals(NarrationJobState.QUEUED, after.single().state)
        assertEquals(null, after.single().error)
    }

    @Test
    fun frontPutsRequestsFirstInOrderAndMovesExistingJobs() {
        val jobs = enqueue(emptyList(), request("c1"), request("c2"), request("c3"))
        val after = enqueue(jobs, request("c3", origin = NarrationOrigin.KEEP_AHEAD), request("c9"), front = true)
        assertEquals(listOf("c3", "c9", "c1", "c2"), after.map { it.chapterId })
    }

    @Test
    fun nextSkipsFailedJobs() {
        val jobs = enqueue(emptyList(), request("c1"), request("c2"))
        val failedFirst = jobs.mapIndexed { index, job -> if (index == 0) job.copy(state = NarrationJobState.FAILED) else job }
        assertEquals("c2", NarrationPlanning.next(failedFirst)?.chapterId)
    }

    @Test
    fun keepAheadUsesTheFixedWindowAfterTheCurrentChapter() {
        val chapters = (1..10).map { Chapter(id = "c$it", downloaded = it != 4) }
        val chosen =
            NarrationPlanning.keepAheadChapters(
                chapters = chapters,
                currentChapterId = "c2",
                count = 3,
                isPlayable = { it.downloaded },
                hasPack = { it == "c3" },
                hasJob = { false },
            )
        // Window c3..c5: c3 is ready, c4 cannot play; never reaches past c5 to make up for them.
        assertEquals(listOf("c5"), chosen)
    }

    @Test
    fun keepAheadIsOffAtZeroAndStopsAtTheStoryEnd() {
        val chapters = (1..3).map { Chapter(id = "c$it", downloaded = true) }
        val none = NarrationPlanning.keepAheadChapters(chapters, "c1", 0, { true }, { false }, { false })
        assertEquals(emptyList<String>(), none)
        val end = NarrationPlanning.keepAheadChapters(chapters, "c2", 5, { true }, { false }, { false })
        assertEquals(listOf("c3"), end)
        val unknown = NarrationPlanning.keepAheadChapters(chapters, "gone", 5, { true }, { false }, { false })
        assertEquals(emptyList<String>(), unknown)
    }

    @Test
    fun keepAheadNeverReachesPastTheMaximum() {
        val chapters = (1..50).map { Chapter(id = "c$it", downloaded = true) }
        val chosen = NarrationPlanning.keepAheadChapters(chapters, "c1", 100, { true }, { false }, { false })
        assertEquals(NarrationPlanning.KEEP_AHEAD_MAX, chosen.size)
        assertEquals("c${NarrationPlanning.KEEP_AHEAD_MAX + 1}", chosen.last())
    }

    @Test
    fun estimatesAndFormatting() {
        assertEquals(62_000L, NarrationPlanning.estimatedAudioMs(1_000, 62.0))
        assertEquals(48_000L, NarrationPlanning.estimatedBytes(1_000))
        assertEquals(500L, NarrationPlanning.generationMs(1_000, 2.0))
        assertEquals("<1 min", NarrationPlanning.formatDuration(30_000))
        assertEquals("1 h 05 min", NarrationPlanning.formatDuration(65 * 60_000L))
        assertEquals(1.0, NarrationPlanning.blend(null, 1.0), 0.0)
    }

    @Test
    fun storedSpeedsAreKeptPerPlaceAndAddUpForComputersSharingChapters() {
        val kokoro = "builtin:kokoro-en-v0_19"
        val speeds = mapOf(kokoro to 1.5, "$kokoro@mac" to 6.0, "$kokoro@bazzite" to 6.5)
        assertEquals(1.5, NarrationPlanning.storedSpeed(speeds, kokoro, null)!!, 0.0)
        assertEquals(6.0, NarrationPlanning.storedSpeed(speeds, kokoro, "mac")!!, 0.0)
        assertEquals(12.5, NarrationPlanning.storedSpeed(speeds, kokoro, "mac+bazzite")!!, 0.0)
        assertEquals(11.0, NarrationPlanning.storedSpeed(speeds + ("$kokoro@mac+bazzite" to 11.0), kokoro, "mac+bazzite")!!, 0.0)
        // A computer never measured: the engine's speed until the pair is.
        assertEquals(1.5, NarrationPlanning.storedSpeed(speeds, kokoro, "mac+studio")!!, 0.0)
        assertEquals(1.5, NarrationPlanning.storedSpeed(speeds, kokoro, "studio")!!, 0.0)
    }
}
