package com.vinicius741.webnovelarchiver.data.storage

import com.vinicius741.webnovelarchiver.ai.AiCoverDraft
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class AiCoverVersionStoreTest {
    private val root = createTempDirectory("cover_versions").toFile()
    private val store = AiCoverDraftStore(root) { it }

    @After
    fun cleanup() {
        AtomicFileWrites.useDefaultOps()
        root.deleteRecursively()
    }

    @Test
    fun experimentsSurviveReplacementApplyAndRestart() {
        val first = AiCoverDraft("first description", byteArrayOf(1), "image/png")
        val second = AiCoverDraft("second description", byteArrayOf(2), "image/jpeg")
        store.saveImage("story", first)
        store.savePrompt("story", second.prompt)
        store.saveImage("story", second)
        store.delete("story")
        val restarted = AiCoverDraftStore(root) { it }
        val versions = restarted.versions.list("story")
        assertEquals(2, versions.size)
        assertTrue(versions.any { it.prompt == first.prompt && it.image.readBytes().contentEquals(first.bytes) })
        assertTrue(versions.any { it.prompt == second.prompt && it.image.readBytes().contentEquals(second.bytes) })
    }

    @Test
    fun preservingAppliedBytesDoesNotEraseKnownPromptOrDuplicateImage() {
        store.saveImage("story", AiCoverDraft("original prompt", byteArrayOf(1), "image/png"))
        store.versions.save("story", AiCoverDraft("", byteArrayOf(1), "image/png"))
        assertEquals(1, store.versions.list("story").size)
        assertEquals(
            "original prompt",
            store.versions
                .list("story")
                .single()
                .prompt,
        )
    }

    @Test
    fun legacyPreviewIsPreservedBeforePromptReplacement() {
        val dir = File(root, "ai_cover_drafts").apply { mkdirs() }
        File(dir, "story.png").writeBytes(byteArrayOf(3))
        File(dir, "story.json").writeText("""{"prompt":"legacy","mediaType":"image/png"}""")
        store.savePrompt("story", "new")
        assertEquals(
            "legacy",
            store.versions
                .list("story")
                .single()
                .prompt,
        )
    }

    @Test
    fun deletingStoryRemovesItsHistoryOnly() {
        store.saveImage("one", AiCoverDraft("one", byteArrayOf(1), "image/png"))
        store.saveImage("two", AiCoverDraft("two", byteArrayOf(2), "image/png"))
        store.delete("one", keepHistory = false)
        assertTrue(store.versions.list("one").isEmpty())
        assertEquals(1, store.versions.list("two").size)
    }

    @Test
    fun failedHistoryCommitLeavesPreviousExperimentIntact() {
        store.saveImage("story", AiCoverDraft("first", byteArrayOf(1), "image/png"))
        AtomicFileWrites.ops =
            object : AtomicFileOps by DefaultAtomicFileOps {
                override fun rename(
                    temp: File,
                    destination: File,
                ): Boolean = destination.extension != "json" && DefaultAtomicFileOps.rename(temp, destination)
            }
        assertTrue(runCatching { store.saveImage("story", AiCoverDraft("second", byteArrayOf(2), "image/png")) }.isFailure)
        assertEquals("first", (store.load("story") as AiCoverDraftRecord.Image).draft.prompt)
        assertEquals(
            "first",
            store.versions
                .list("story")
                .single()
                .prompt,
        )
    }

    @Test
    fun legacyGalleryListingNeverWritesEvenWhenWritesWouldFail() {
        val applied = File(root, "legacy.jpg").apply { writeBytes(byteArrayOf(7, 8)) }
        AtomicFileWrites.ops =
            object : AtomicFileOps by DefaultAtomicFileOps {
                override fun rename(
                    temp: File,
                    destination: File,
                ): Boolean = error("Listing must not write")
            }
        repeat(2) {
            val version = store.versions.listForDisplay("story", applied, true).single()
            assertTrue(version.isCurrent)
            assertEquals(applied, version.image)
        }
        assertEquals(listOf("legacy.jpg"), root.listFiles()!!.map { it.name })
    }

    @Test
    fun currentVersionFollowsAppliedFileRevisionWithoutDuplicatingSavedVersion() {
        val first = AiCoverDraft("first", byteArrayOf(1), "image/png")
        store.saveImage("story", first)
        val applied = File(root, "applied.png").apply { writeBytes(first.bytes) }
        assertEquals(1, store.versions.listForDisplay("story", applied, true).size)
        applied.writeBytes(byteArrayOf(2, 3))
        val changed = store.versions.listForDisplay("story", applied, true)
        assertEquals(2, changed.size)
        assertEquals(applied, changed.single { it.isCurrent }.image)
        assertTrue(store.versions.listForDisplay("story", applied, false).none { it.isCurrent })
        assertEquals(
            "first",
            store.versions
                .list("story")
                .single()
                .prompt,
        )
    }
}
