package com.vinicius741.webnovelarchiver.data.storage

import com.vinicius741.webnovelarchiver.ai.AiCoverDraft
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * Round-trip coverage for the pending AI cover draft store: a background-generated draft must
 * survive exactly as it was persisted (prompt, bytes, media type), a fresh prompt must invalidate
 * a painted preview, and delete must clear both stages. These files are the user's only copy of a
 * billable image until Apply/Discard, so partial or stale recovery is a real data-loss bug.
 */
class AiCoverDraftStoreTest {
    private lateinit var root: File
    private lateinit var store: AiCoverDraftStore

    @Before
    fun setUp() {
        root = createTempDirectory("ai_cover_drafts").toFile()
        // Mirror AppStorage.safeName: story ids are arbitrary source-site strings.
        store = AiCoverDraftStore(root) { it.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120) }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun loadReturnsNullWhenNothingSaved() {
        assertNull(store.load("story-1"))
    }

    @Test
    fun imageDraftRoundTripsWithBytesAndMediaType() {
        val draft = AiCoverDraft(prompt = "ink painting of a wandering swordsman", bytes = byteArrayOf(1, 2, 3, 4), mediaType = "image/png")

        store.saveImage("story-1", draft)

        val loaded = store.load("story-1")
        assertTrue(loaded is AiCoverDraftRecord.Image)
        loaded as AiCoverDraftRecord.Image
        assertEquals(draft.prompt, loaded.draft.prompt)
        assertTrue(draft.bytes.contentEquals(loaded.draft.bytes))
        assertEquals(draft.mediaType, loaded.draft.mediaType)
    }

    @Test
    fun promptOnlyRoundTripsWithoutImage() {
        store.savePrompt("story-1", "portrait of the alchemist's tower")

        val loaded = store.load("story-1")
        assertTrue(loaded is AiCoverDraftRecord.PromptOnly)
        loaded as AiCoverDraftRecord.PromptOnly
        assertEquals("portrait of the alchemist's tower", loaded.prompt)
    }

    @Test
    fun freshPromptInvalidatesPaintedPreview() {
        store.saveImage("story-1", AiCoverDraft("old prompt", byteArrayOf(9), "image/png"))

        store.savePrompt("story-1", "new prompt")

        val loaded = store.load("story-1")
        assertTrue(loaded is AiCoverDraftRecord.PromptOnly)
        assertEquals("new prompt", (loaded as AiCoverDraftRecord.PromptOnly).prompt)
        assertNull(File(root, "ai_cover_drafts").listFiles()?.firstOrNull { it.extension != "json" })
    }

    @Test
    fun repaintReplacesThePreviousImageFilePerExtension() {
        store.saveImage("story-1", AiCoverDraft("p", byteArrayOf(1), "image/png"))
        store.saveImage("story-1", AiCoverDraft("p", byteArrayOf(2), "image/jpeg"))

        val images = File(root, "ai_cover_drafts").listFiles()?.filter { it.extension != "json" }
        assertEquals(1, images?.size)
        val loaded = store.load("story-1") as AiCoverDraftRecord.Image
        assertTrue(byteArrayOf(2).contentEquals(loaded.draft.bytes))
        assertEquals("image/jpeg", loaded.draft.mediaType)
    }

    @Test
    fun missingImageDegradesToPromptOnlyRecord() {
        store.saveImage("story-1", AiCoverDraft("the prompt", byteArrayOf(1), "image/png"))

        File(root, "ai_cover_drafts").listFiles()!!.first { it.extension == "png" }.delete()

        val loaded = store.load("story-1")
        assertTrue(loaded is AiCoverDraftRecord.PromptOnly)
        assertEquals("the prompt", (loaded as AiCoverDraftRecord.PromptOnly).prompt)
    }

    @Test
    fun deleteClearsPromptAndImage() {
        store.saveImage("story-1", AiCoverDraft("p", byteArrayOf(1), "image/png"))

        store.delete("story-1")

        assertNull(store.load("story-1"))
        assertEquals(0, File(root, "ai_cover_drafts").listFiles()?.size)
    }

    @Test
    fun storyIdsAreIsolatedBySafeName() {
        val one = AiCoverDraft("one", byteArrayOf(1), "image/png")
        val two = AiCoverDraft("two", byteArrayOf(2), "image/png")
        store.saveImage("story/one", one)
        store.saveImage("story:two", two)

        val loadedOne = store.load("story/one") as AiCoverDraftRecord.Image
        val loadedTwo = store.load("story:two") as AiCoverDraftRecord.Image

        assertTrue(one.bytes.contentEquals(loadedOne.draft.bytes))
        assertTrue(two.bytes.contentEquals(loadedTwo.draft.bytes))
    }
}
