package com.vinicius741.webnovelarchiver.narration

import com.vinicius741.webnovelarchiver.tts.TtsWavWriter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class NarrationGeneratorTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val voice = NarrationVoice("fake-engine", "v1", 1f, "Fake")
    private lateinit var store: NarrationStore
    private lateinit var queue: NarrationQueue
    private val source = FakeSource()
    private val synthesizers = FakeSynthesizers()
    private lateinit var generator: NarrationGenerator

    private class FakeSource : NarrationChapterSource {
        var sentences = listOf("First sentence.", "Second sentence.", "Third sentence.")
        var title: String? = "Chapter One"
        var exists = true
        var generation = 1L
        var free = Long.MAX_VALUE

        /** Runs before a read, so a test can change the text while a chapter is generating. */
        var beforeRead: (Int) -> Unit = {}
        private var reads = 0

        override suspend fun chapter(
            storyId: String,
            chapterId: String,
        ): NarrationChapterText? {
            beforeRead(reads++)
            return if (exists) NarrationChapterText("Story", "Chapter One", sentences, title) else null
        }

        override fun libraryGeneration(): Long = generation

        override fun freeBytes(): Long = free
    }

    private class FakeSynthesizers : NarrationSynthesizerFactory {
        val spoken = mutableListOf<String>()
        var failOn: String? = null
        var unavailable = false
        var onSpeak: (String) -> Unit = {}

        override suspend fun open(voice: NarrationVoice): NarrationSynthesizer {
            if (unavailable) throw NarrationVoiceUnavailable("Engine missing")
            return object : NarrationSynthesizer {
                override val voice = voice
                override val voiceKey = "fake|${voice.voiceId}"

                override suspend fun synthesize(
                    text: String,
                    output: File,
                ): Boolean {
                    if (text == failOn) return false
                    spoken += text
                    onSpeak(text)
                    output.writeBytes(TtsWavWriter.mono16(FloatArray(2_400) { 0.5f }, 24_000))
                    return true
                }

                override fun close() = Unit
            }
        }
    }

    @Before
    fun setUp() {
        val root = folder.newFolder()
        store = NarrationStore(root).apply { load() }
        queue = NarrationQueue(File(root, "queue.json")).apply { load() }
        generator = NarrationGenerator(store, queue, source, synthesizers)
    }

    private fun enqueue(jobVoice: NarrationVoice = voice): NarrationJob {
        queue.update {
            it.copy(
                jobs =
                    NarrationPlanning.enqueue(
                        it.jobs,
                        listOf(NarrationRequest("s", "c", jobVoice, NarrationOrigin.REGENERATE)),
                        { _, _ -> false },
                        false,
                        0L,
                    ) { "job-${it.jobs.size}" },
            )
        }
        return queue.state.value.jobs
            .last()
    }

    @Test
    fun generatesEverySentenceAndTheTitleThenPublishes() =
        runTest {
            val job = enqueue()
            generator.runJob(job)
            val pack = store.pack("s", "c")
            assertNotNull(pack)
            assertEquals(listOf("Chapter One", "First sentence.", "Second sentence.", "Third sentence."), synthesizers.spoken)
            assertEquals(3, pack!!.index.sentenceDurationsMs.size)
            assertTrue(pack.index.sentenceDurationsMs.all { it in 1..100 })
            assertNotNull(pack.index.titleDurationMs)
            assertTrue(
                queue.state.value.jobs
                    .isEmpty(),
            )
        }

    @Test
    fun aKilledJobResumesAtTheFirstMissingClip() =
        runTest {
            val job = enqueue()
            // Pausing after the second clip stands in for the process dying there.
            synthesizers.onSpeak = { if (it == "First sentence.") queue.update { doc -> doc.copy(paused = true) } }
            generator.runJob(job)
            assertNull(store.pack("s", "c"))
            synthesizers.spoken.clear()
            synthesizers.onSpeak = {}
            queue.update { it.copy(paused = false) }
            val restarted = NarrationGenerator(store, NarrationQueue(File(store.root, "queue.json")).apply { load() }, source, synthesizers)
            restarted.runJob(
                queue.state.value.jobs
                    .single(),
            )
            assertEquals(listOf("Second sentence.", "Third sentence."), synthesizers.spoken)
            assertNotNull(store.pack("s", "c"))
        }

    @Test
    fun textChangedWhileGeneratingIsNotPublished() =
        runTest {
            val job = enqueue()
            source.beforeRead = { read -> if (read == 1) source.sentences = listOf("First sentence.", "A new second sentence.") }
            generator.runJob(job)
            assertNull(store.pack("s", "c"))
            assertNull(
                queue.state.value.jobs
                    .single()
                    .version,
            )
            // The rerun regenerates from the new text and reuses nothing it cannot.
            generator.runJob(
                queue.state.value.jobs
                    .single(),
            )
            assertEquals(listOf("First sentence.", "A new second sentence."), store.pack("s", "c")!!.recipe.sentences)
        }

    @Test
    fun aRestoredLibraryDiscardsTheWork() =
        runTest {
            val job = enqueue()
            source.beforeRead = { read -> if (read == 1) source.generation = 2 }
            generator.runJob(job)
            assertNull(store.pack("s", "c"))
            assertFalse(
                store
                    .chapterDir("s", "c")
                    .listFiles()
                    .orEmpty()
                    .any { it.isDirectory },
            )
        }

    @Test
    fun aRemovedJobIsDroppedWithItsWork() =
        runTest {
            val job = enqueue()
            synthesizers.onSpeak = { if (it == "First sentence.") queue.update { doc -> doc.copy(jobs = emptyList()) } }
            generator.runJob(job)
            assertNull(store.pack("s", "c"))
            assertFalse(
                store
                    .chapterDir("s", "c")
                    .listFiles()
                    .orEmpty()
                    .any { it.isDirectory },
            )
        }

    @Test
    fun regeneratingInTheSameVoiceReusesUnchangedSentences() =
        runTest {
            generator.runJob(enqueue())
            synthesizers.spoken.clear()
            source.sentences = listOf("First sentence.", "Edited second sentence.", "Third sentence.")
            generator.runJob(enqueue())
            assertEquals(listOf("Edited second sentence."), synthesizers.spoken)
            assertEquals("Edited second sentence.", store.pack("s", "c")!!.recipe.sentences[1])
        }

    @Test
    fun anotherVoiceRegeneratesEverything() =
        runTest {
            generator.runJob(enqueue())
            synthesizers.spoken.clear()
            generator.runJob(enqueue(voice.copy(voiceId = "v2", label = "Other")))
            assertEquals(4, synthesizers.spoken.size)
            assertEquals(
                "v2",
                store
                    .pack("s", "c")!!
                    .recipe.voice.voiceId,
            )
        }

    @Test
    fun anUnchangedChapterInTheSameVoiceIsDoneAtOnce() =
        runTest {
            generator.runJob(enqueue())
            val first = store.pack("s", "c")!!.dir
            synthesizers.spoken.clear()
            generator.runJob(enqueue())
            assertTrue(synthesizers.spoken.isEmpty())
            assertEquals(first, store.pack("s", "c")!!.dir)
        }

    @Test
    fun aSentenceThatKeepsFailingFailsTheJobAndKeepsTheRest() =
        runTest {
            synthesizers.failOn = "Second sentence."
            generator.runJob(enqueue())
            val job =
                queue.state.value.jobs
                    .single()
            assertEquals(NarrationJobState.FAILED, job.state)
            assertTrue(job.error!!.contains("Second sentence"))
            assertNull(store.pack("s", "c"))
        }

    @Test
    fun anUnavailableVoiceFailsWithoutSubstituting() =
        runTest {
            synthesizers.unavailable = true
            generator.runJob(enqueue())
            assertEquals(
                "Engine missing",
                queue.state.value.jobs
                    .single()
                    .error,
            )
            assertTrue(synthesizers.spoken.isEmpty())
        }

    @Test
    fun lowStoragePausesTheQueueInsteadOfFailing() =
        runTest {
            source.free = 1L
            generator.runJob(enqueue())
            val document = queue.state.value
            assertTrue(document.paused)
            assertEquals("Not enough storage", document.pauseReason)
            assertEquals(NarrationJobState.QUEUED, document.jobs.single().state)
        }

    @Test
    fun aMissingChapterDropsTheJob() =
        runTest {
            source.exists = false
            generator.runJob(enqueue())
            assertTrue(
                queue.state.value.jobs
                    .isEmpty(),
            )
        }

    @Test
    fun removalDuringTheFinalReadCannotPublish() =
        runTest {
            val job = enqueue()
            source.beforeRead = { read -> if (read == 1) queue.update { it.copy(jobs = emptyList()) } }
            generator.runJob(job)
            assertNull(store.pack("s", "c"))
            assertTrue(
                queue.state.value.jobs
                    .isEmpty(),
            )
            assertTrue(
                store
                    .chapterDir("s", "c")
                    .listFiles()
                    .orEmpty()
                    .none { it.isDirectory },
            )
        }

    @Test
    fun voiceReplacementDuringTheFinalReadKeepsTheNewRequest() =
        runTest {
            val job = enqueue()
            val replacement = voice.copy(voiceId = "v2")
            source.beforeRead = { read -> if (read == 1) enqueue(replacement) }
            generator.runJob(job)
            assertNull(store.pack("s", "c"))
            assertEquals(
                replacement,
                queue.state.value.jobs
                    .single()
                    .voice,
            )
            generator.runJob(
                queue.state.value.jobs
                    .single(),
            )
            assertEquals(replacement, store.pack("s", "c")!!.recipe.voice)
        }

    @Test
    fun pauseDuringTheFinalReadDefersPublicationUntilResume() =
        runTest {
            val job = enqueue()
            source.beforeRead = { read -> if (read == 1) queue.update { it.copy(paused = true) } }
            generator.runJob(job)
            assertNull(store.pack("s", "c"))
            assertEquals(1, queue.state.value.jobs.size)
            synthesizers.spoken.clear()
            queue.update { it.copy(paused = false) }
            generator.runJob(
                queue.state.value.jobs
                    .single(),
            )
            assertTrue(synthesizers.spoken.isEmpty())
            assertNotNull(store.pack("s", "c"))
        }

    @Test
    fun aFailedOldVoiceCannotFailItsReplacement() =
        runTest {
            val job = enqueue()
            val replacement = voice.copy(voiceId = "v2")
            synthesizers.onSpeak = {
                enqueue(replacement)
                error("Old voice failed")
            }
            generator.runJob(job)
            assertEquals(
                replacement,
                queue.state.value.jobs
                    .single()
                    .voice,
            )
            assertEquals(
                NarrationJobState.QUEUED,
                queue.state.value.jobs
                    .single()
                    .state,
            )
        }

    @Test
    fun workWaitsForTheForegroundServiceAndResumesAfterItReturns() =
        runTest {
            enqueue()
            val allowed = MutableStateFlow(false)
            val worker = NarrationGenerator(store, queue, source, synthesizers, generationAllowed = allowed)
            backgroundScope.launch { worker.runLoop() }
            runCurrent()
            assertTrue(synthesizers.spoken.isEmpty())
            synthesizers.onSpeak = { allowed.value = false }
            allowed.value = true
            runCurrent()
            assertEquals(listOf("Chapter One"), synthesizers.spoken)
            assertNull(store.pack("s", "c"))
            assertNull(worker.progress.value)
            synthesizers.onSpeak = {}
            allowed.value = true
            runCurrent()
            assertEquals(listOf("Chapter One", "First sentence.", "Second sentence.", "Third sentence."), synthesizers.spoken)
            assertNotNull(store.pack("s", "c"))
        }
}
