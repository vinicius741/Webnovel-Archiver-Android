package com.vinicius741.webnovelarchiver.narration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vinicius741.webnovelarchiver.cleanup.TtsTextPreparation
import com.vinicius741.webnovelarchiver.data.repository.AppRepository
import com.vinicius741.webnovelarchiver.data.storage.AppStorage
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.simulation.SimulationContext
import com.vinicius741.webnovelarchiver.tts.TtsPcmClip
import com.vinicius741.webnovelarchiver.tts.kokoro.KokoroRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses disposable storage in the instrumentation app, with no speech engine or live requests. */
@RunWith(AndroidJUnit4::class)
class NarrationCoordinatorDeviceTest {
    private val voice = NarrationVoice("test-engine", "test-voice", 1f, "Test")

    @Test
    fun staleAudioCanBeQueuedManuallyAndByPlaybackWhileReadyAudioIsSkipped() =
        runBlocking {
            val base = ApplicationProvider.getApplicationContext<Context>()
            val context = SimulationContext(base, "narration-review-${System.nanoTime()}")
            val repository = AppRepository(AppStorage(context))
            val story =
                Story(
                    id = "story",
                    title = "Test story",
                    chapters = mutableListOf(Chapter(id = "chapter", content = "<p>Original sentence.</p>", downloaded = true)),
                )
            repository.addOrUpdateStory(story)
            val coordinator = NarrationCoordinator(context, repository, KokoroRuntime())
            coordinator.init {}
            coordinator.awaitLoaded()
            coordinator.awaitQueueLoaded()
            try {
                val recipe =
                    NarrationRecipe(
                        storyId = story.id,
                        chapterId = "chapter",
                        chunkingVersion = TtsTextPreparation.CHUNKING_VERSION,
                        textFingerprint = NarrationPlanning.fingerprint(listOf("Original sentence."), TtsTextPreparation.CHUNKING_VERSION),
                        sentences = listOf("Original sentence."),
                        voice = voice,
                        voiceKey = "test-key",
                    )
                val dir = coordinator.store.createVersion(recipe)
                coordinator.store.writeClip(coordinator.store.clipFile(dir, 0), TtsPcmClip(24_000, ByteArray(480)))
                coordinator.store.publish(recipe, dir, NarrationPackIndex(sampleRate = 24_000, sentenceDurationsMs = listOf(10)))

                coordinator.enqueue(story.id, listOf("chapter"), voice)
                assertTrue(coordinator.isReady(story.id, "chapter"))
                assertTrue(
                    "Current audio should not be queued again",
                    coordinator.queueState.value.jobs
                        .isEmpty(),
                )

                // A cleanup can change the text without changing downloadedAt.
                story.chapters[0] = story.chapters[0].copy(content = "<p>Edited sentence.</p>")
                repository.addOrUpdateStory(story)
                assertFalse("Text edits must invalidate the readiness cache", coordinator.isReady(story.id, "chapter"))
                coordinator.enqueue(story.id, listOf("chapter"), voice)
                val manual =
                    coordinator.queueState.value.jobs
                        .single()
                assertEquals(NarrationOrigin.MANUAL, manual.origin)
                coordinator.remove(setOf(manual.id))

                coordinator.enqueue(story.id, listOf("chapter"), voice, NarrationOrigin.KEEP_AHEAD, front = true)
                val automatic =
                    coordinator.queueState.value.jobs
                        .single()
                assertEquals(NarrationOrigin.KEEP_AHEAD, automatic.origin)
                assertEquals(NarrationJobState.QUEUED, automatic.state)
                assertNull("Without a foreground service the worker must stay idle", coordinator.progress.value)
            } finally {
                coordinator.pause()
                context.filesDir.deleteRecursively()
            }
        }
}
