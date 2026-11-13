package com.vinicius741.webnovelarchiver.narration

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Files as release 1.0.18/1.0.19 wrote them: field keys renamed by R8 (see their mapping.txt). */
class NarrationLegacyJsonTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val gson = Gson()
    private val legacyVoice = """{"a":"builtin:kokoro-en-v0_19","b":"kokoro-en-v0_19:af_bella","c":1.0,"d":"Bella · Kokoro"}"""
    private val legacyJob =
        """{"a":"job-1","b":"rr_132259","c":"2668365","d":$legacyVoice,"e":"MANUAL","f":"QUEUED","h":"v-1234","i":1794451703956}"""
    private val legacyQueue =
        """{"a":[$legacyJob],"b":false,"d":5,"e":{"builtin:kokoro-en-v0_19":44.5},"f":{"builtin:kokoro-en-v0_19":1.48},"g":true}"""
    private val legacyRecipe =
        """{"a":"rr_132259","b":"2665538","c":2,"d":"abc123","e":"Chapter 12","f":["One.","Two."],"g":$legacyVoice,""" +
            """"h":"builtin:kokoro-en-v0_19|speaker=1|text=2"}"""
    private val legacyIndex = """{"a":"pcm","b":24000,"c":[1200,3400],"d":900,"e":5500,"f":264000,"g":1794451441262}"""

    @Test
    fun aLegacyQueueLoadsWithItsJobsAndVoices() {
        val file = File(folder.newFolder(), "queue.json").apply { writeText(legacyQueue) }
        val queue = NarrationQueue(file).apply { load() }
        val document = queue.state.value
        val job = document.jobs.single()
        assertEquals("rr_132259", job.storyId)
        assertEquals("2668365", job.chapterId)
        assertEquals(NarrationOrigin.MANUAL, job.origin)
        assertEquals(NarrationJobState.QUEUED, job.state)
        assertEquals("v-1234", job.version)
        assertEquals("kokoro-en-v0_19:af_bella", job.voice.voiceId)
        assertEquals("Bella · Kokoro", job.voice.label)
        assertEquals(5, document.keepAhead)
        assertEquals(1.48, document.speed.getValue("builtin:kokoro-en-v0_19"), 0.0)
        assertEquals(true, document.screenOffPromptShown)
    }

    @Test
    fun aLegacyRecipeAndIndexKeepTheirTypes() {
        val recipe = gson.fromJson(NarrationLegacyJson.recipe(legacyRecipe), NarrationRecipe::class.java)
        assertEquals("rr_132259", recipe.storyId)
        assertEquals(listOf("One.", "Two."), recipe.sentences)
        assertEquals("Chapter 12", recipe.title)
        assertEquals("builtin:kokoro-en-v0_19|speaker=1|text=2", recipe.voiceKey)
        assertEquals("builtin:kokoro-en-v0_19", recipe.voice.engine)
        val index = gson.fromJson(NarrationLegacyJson.index(legacyIndex), NarrationPackIndex::class.java)
        // The crash: durations must come back as Longs, not Doubles.
        val first: Long = index.sentenceDurationsMs.first()
        assertEquals(1200L, first)
        assertEquals(24_000, index.sampleRate)
        assertEquals(900L, index.titleDurationMs)
        assertEquals(1, index.format)
    }

    @Test
    fun aLegacyPackIsReadByTheStore() {
        val root = folder.newFolder()
        val store = NarrationStore(root).apply { load() }
        val chapterDir = store.chapterDir("rr_132259", "2665538")
        File(chapterDir, "v-legacy").apply {
            mkdirs()
            File(this, "recipe.json").writeText(legacyRecipe)
            File(this, "index.json").writeText(legacyIndex)
        }
        File(chapterDir, "current").writeText("v-legacy")
        val reloaded = NarrationStore(root).apply { load() }
        val pack = reloaded.pack("rr_132259", "2665538")!!
        assertEquals(listOf(1200L, 3400L), pack.index.sentenceDurationsMs)
        assertEquals("abc123", pack.recipe.textFingerprint)
    }

    @Test
    fun currentFilesPassThroughUnchanged() {
        val current = gson.toJson(NarrationQueueDocument(keepAhead = 3, pauseReason = "Not enough storage"))
        assertEquals(current, NarrationLegacyJson.queue(current))
        val index = gson.toJson(NarrationPackIndex(sampleRate = 24_000, sentenceDurationsMs = listOf(5L)))
        assertEquals(index, NarrationLegacyJson.index(index))
        assertNull(gson.fromJson(NarrationLegacyJson.queue(current), NarrationQueueDocument::class.java).jobs.firstOrNull())
    }

    @Test
    fun theOneComputerEarlierVersionsChoseBecomesTheFirstChosen() {
        val servers = """[{"id":"mac","name":"Mac","address":"192.168.1.20","token":"abcd-efgh-jkmn"}]"""
        val file = File(folder.newFolder(), "queue.json").apply { writeText("""{"jobs":[],"servers":$servers,"generateOn":"mac"}""") }
        val document = NarrationQueue(file).apply { load() }.state.value
        assertEquals(listOf("mac"), document.generateOnIds)
        assertEquals(false, document.phoneFallback)
        assertNull(document.generateOn)
        assertEquals(
            "Mac",
            document.places.servers
                .single()
                .name,
        )

        // A computer that was removed is not chosen.
        file.writeText("""{"jobs":[],"servers":$servers,"generateOnIds":["gone","mac"],"phoneFallback":true}""")
        val current = NarrationQueue(file).apply { load() }.state.value
        assertEquals(listOf("mac"), current.generateOnIds)
        assertEquals(true, current.phoneFallback)
    }
}
