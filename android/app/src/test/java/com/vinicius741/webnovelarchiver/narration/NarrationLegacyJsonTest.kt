package com.vinicius741.webnovelarchiver.narration

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NarrationLegacyJsonTest {
    @get:Rule
    val folder = TemporaryFolder()

    // Field aliases verified against the published v1.0.19-native APK, map id b45ec751.
    private val voice = """{"a":"engine","b":"voice","c":1.2,"d":"Voice"}"""

    @Test
    fun legacyQueueRestoresTypedJobsAndWritesStableNames() {
        val file = folder.newFile("queue.json")
        file.writeText(
            """{"a":[{"a":"job","b":"story","c":"chapter","d":$voice,
                "e":"KEEP_AHEAD","f":"FAILED","g":"retry","h":"v-old","i":123}],
                "b":true,"c":"Paused","d":3,"e":{"engine":12.5},"f":{"engine":2.5},"g":true}""",
        )
        val queue = NarrationQueue(file).apply { load() }
        val document = queue.state.value
        val job = document.jobs.single()
        assertEquals("story", job.storyId)
        assertEquals("chapter", job.chapterId)
        assertEquals(NarrationVoice("engine", "voice", 1.2f, "Voice"), job.voice)
        assertEquals(NarrationOrigin.KEEP_AHEAD, job.origin)
        assertEquals(NarrationJobState.FAILED, job.state)
        assertEquals("retry", job.error)
        assertEquals("v-old", job.version)
        assertEquals(123L, job.enqueuedAt)
        assertTrue(document.paused)
        assertEquals("Paused", document.pauseReason)
        assertEquals(3, document.keepAhead)
        assertEquals(12.5, document.msPerChar.getValue("engine"), 0.0)
        assertEquals(2.5, document.speed.getValue("engine"), 0.0)
        assertTrue(document.screenOffPromptShown)
        queue.update { it.copy(paused = false) }
        val saved = file.readText()
        assertTrue(saved.contains("\"jobs\""))
        assertFalse(saved.contains("\"a\""))
        assertEquals(queue.state.value, NarrationQueue(file).apply { load() }.state.value)
    }

    @Test
    fun legacyPublishedPackRestoresLongDurationsAndVoice() {
        val store = NarrationStore(folder.newFolder())
        val chapterDir = store.chapterDir("story", "chapter")
        val version = File(chapterDir, "v-old").apply { mkdirs() }
        File(chapterDir, "current").writeText(version.name)
        File(version, "recipe.json").writeText(
            """{"a":"story","b":"chapter","c":2,"d":"fingerprint","e":"Title",
                "f":["One.","Two."],"g":$voice,"h":"voice-key"}""",
        )
        File(version, "index.json").writeText(
            """{"a":"pcm","b":24000,"c":[1000,2000],"d":500,"e":3500,"f":168000,"g":123}""",
        )
        store.load()
        val pack = requireNotNull(store.pack("story", "chapter"))
        assertEquals(listOf("One.", "Two."), pack.recipe.sentences)
        assertEquals("fingerprint", pack.recipe.textFingerprint)
        assertEquals("Title", pack.recipe.title)
        assertEquals("voice-key", pack.recipe.voiceKey)
        assertEquals(NarrationVoice("engine", "voice", 1.2f, "Voice"), pack.recipe.voice)
        assertEquals(1, pack.index.format)
        assertEquals("pcm", pack.index.codec)
        assertEquals(24000, pack.index.sampleRate)
        assertEquals(3000L, pack.index.sentenceDurationsMs.sum())
        assertEquals(1000L, pack.index.sentenceDurationsMs[0])
        assertEquals(500L, pack.index.titleDurationMs)
        assertEquals(3500L, pack.index.totalDurationMs)
        assertEquals(168000L, pack.index.bytes)
        assertEquals(123L, pack.index.generatedAt)
        val json = Gson().toJson(pack.index)
        assertTrue(json.contains("\"sentenceDurationsMs\""))
        assertEquals(pack.index, Gson().fromJson(json, NarrationPackIndex::class.java))
    }
}
