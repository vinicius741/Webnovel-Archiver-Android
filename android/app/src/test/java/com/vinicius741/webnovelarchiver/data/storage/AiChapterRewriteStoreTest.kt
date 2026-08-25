package com.vinicius741.webnovelarchiver.data.storage

import com.vinicius741.webnovelarchiver.domain.model.ChapterRewriteDraftRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class AiChapterRewriteStoreTest {
    private val root = Files.createTempDirectory("chapter-rewrites").toFile()
    private val store = AiChapterRewriteStore(root) { value -> value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120) }

    @Test
    fun `save draft then apply promotes it to the active variant`() {
        store.saveDraft(STORY, draft(chapterId = "ch1", status = "ready"), POLISHED_HTML)
        assertEquals(POLISHED_HTML, store.draftHtml(STORY, "ch1"))

        val applied = store.applyDraft(STORY, "ch1")
        assertNotNull(applied)
        assertTrue(applied!!.active)
        assertEquals(POLISHED_HTML, store.appliedHtml(STORY, "ch1"))
        assertNull(store.draftRecord(STORY, "ch1"))
        assertNull(store.draftHtml(STORY, "ch1"))
    }

    @Test
    fun `toggling active off serves source again without deleting the file`() {
        store.saveDraft(STORY, draft(), POLISHED_HTML)
        store.applyDraft(STORY, "ch1")
        val toggled = store.setActive(STORY, "ch1", false)
        assertEquals(false, toggled!!.active)
        assertNull(store.appliedHtml(STORY, "ch1"))
        val back = store.setActive(STORY, "ch1", true)
        assertEquals(POLISHED_HTML, store.appliedHtml(STORY, "ch1"))
        assertTrue(back!!.active)
    }

    @Test
    fun `discard draft removes content and record but keeps applied variants`() {
        store.saveDraft(STORY, draft(chapterId = "ch2"), POLISHED_HTML)
        store.discardDraft(STORY, "ch2")
        assertNull(store.draftRecord(STORY, "ch2"))
        assertNull(store.draftHtml(STORY, "ch2"))

        store.saveDraft(STORY, draft(chapterId = "ch3"), POLISHED_HTML)
        store.applyDraft(STORY, "ch3")
        store.saveDraft(STORY, draft(chapterId = "ch3", operationId = "op2"), POLISHED_HTML + "<p>more</p>")
        store.discardDraft(STORY, "ch3")
        assertNull(store.draftRecord(STORY, "ch3"))
        assertNotNull(store.appliedRecord(STORY, "ch3"))
    }

    @Test
    fun `remove rewrite deletes the applied variant entirely`() {
        store.saveDraft(STORY, draft(), POLISHED_HTML)
        store.applyDraft(STORY, "ch1")
        store.removeRewrite(STORY, "ch1")
        assertNull(store.appliedRecord(STORY, "ch1"))
        assertNull(store.appliedHtml(STORY, "ch1"))
        assertTrue(store.manifest(STORY).applied.isEmpty())
    }

    @Test
    fun `deleting the story removes its whole folder`() {
        store.saveDraft(STORY, draft(), POLISHED_HTML)
        store.applyDraft(STORY, "ch1")
        store.delete(STORY)
        assertTrue(store.manifest(STORY).applied.isEmpty())
        assertNull(store.appliedHtml(STORY, "ch1"))
    }

    @Test
    fun `corrupt manifest degrades to empty instead of crashing`() {
        store.saveDraft(STORY, draft(), POLISHED_HTML)
        val manifest = java.io.File(root, "chapter_rewrites/$STORY/manifest.json")
        manifest.writeText("{ this is not json")
        assertTrue(store.manifest(STORY).drafts.isEmpty())
        assertNull(store.draftHtml(STORY, "ch1"))
        // Recovery: the next save rewrites the manifest cleanly.
        store.saveDraft(STORY, draft(chapterId = "ch9"), POLISHED_HTML)
        assertNotNull(store.draftRecord(STORY, "ch9"))
    }

    @Test
    fun `backup payload carries applied files and a drafts-stripped manifest`() {
        // Drafts-only staging has nothing durable to back up.
        store.saveDraft(STORY, draft(), POLISHED_HTML)
        assertNull(store.backupPayloadForStory(STORY))

        store.applyDraft(STORY, "ch1")
        store.saveDraft(STORY, draft(chapterId = "ch2"), POLISHED_HTML)
        val payload = store.backupPayloadForStory(STORY) ?: error("expected a backup payload")
        assertEquals("chapter_rewrites/$STORY/manifest.json", payload.manifestPath)
        assertEquals(
            listOf("chapter_rewrites/$STORY/${store.fileStem("ch1")}/applied.html"),
            payload.appliedFiles.map { it.first },
        )
        // The pending ch2 draft never ships: its record is stripped from the backed-up manifest,
        // and no draft.html path is listed.
        assertTrue(payload.manifestJson.contains("\"drafts\":{}"))
        assertFalse(payload.manifestJson.contains("ch2"))
        assertFalse(payload.appliedFiles.any { it.first.contains("draft") })
    }

    @Test
    fun `backup payload is keyed by the raw story id not the safe-named directory`() {
        val unicodeStory = "rr_中文故事"
        store.saveDraft(unicodeStory, draft(chapterId = "ch1"), POLISHED_HTML)
        store.applyDraft(unicodeStory, "ch1")
        val payload = store.backupPayloadForStory(unicodeStory) ?: error("expected a backup payload")
        // Every path must round-trip through safeName so restore re-resolves the same directory.
        val expectedDir = unicodeStory.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)
        assertEquals("chapter_rewrites/$expectedDir/manifest.json", payload.manifestPath)
        assertEquals(
            "chapter_rewrites/$expectedDir/${store.fileStem("ch1")}/applied.html",
            payload.appliedFiles.single().first,
        )
    }

    @Test
    fun `chapter ids that safe-name to the same stem stay distinct`() {
        store.saveDraft(STORY, draft(chapterId = "a-b"), POLISHED_HTML)
        store.saveDraft(STORY, draft(chapterId = "a_b"), "<p>other</p>")
        assertEquals(POLISHED_HTML, store.draftHtml(STORY, "a-b"))
        assertEquals("<p>other</p>", store.draftHtml(STORY, "a_b"))
    }

    private fun draft(
        chapterId: String = "ch1",
        status: String = "ready",
        operationId: String = "op1",
    ): ChapterRewriteDraftRecord =
        ChapterRewriteDraftRecord(
            storyId = STORY,
            chapterId = chapterId,
            chapterTitle = "Chapter $chapterId",
            sourceSha256 = "abc123",
            createdAt = 1234567L,
            model = "openai/gpt-5.6-terra",
            verifierModel = "x-ai/grok-4.6",
            promptVersion = "v1.2-light",
            strength = "light",
            operationId = operationId,
            costUsd = "0.158",
            status = status,
            verification =
                com.vinicius741.webnovelarchiver.domain.model.RewriteVerificationSummary(
                    status = "verified",
                    verifierModel = "x-ai/grok-4.6",
                ),
            mergedBlocks = 31,
        )

    private companion object {
        const val STORY = "rr_165465"
        const val POLISHED_HTML = "<p>Polished carrier paragraph.</p>"
    }
}
