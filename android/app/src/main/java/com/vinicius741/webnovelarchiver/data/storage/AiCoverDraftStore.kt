package com.vinicius741.webnovelarchiver.data.storage

import com.google.gson.Gson
import com.vinicius741.webnovelarchiver.ai.AiCoverDraft
import com.vinicius741.webnovelarchiver.ai.AiCoverPlanning
import java.io.File

/**
 * A generated-but-unapplied AI cover draft recovered from disk. [PromptOnly] is the staged flow's
 * editable image prompt (stage 1 result); [Image] adds the painted preview (stage 2 result).
 */
sealed interface AiCoverDraftRecord {
    data class PromptOnly(
        val prompt: String,
    ) : AiCoverDraftRecord

    data class Image(
        val draft: AiCoverDraft,
    ) : AiCoverDraftRecord
}

/**
 * Owns the pending (preview-only) AI cover drafts under `ai_cover_drafts/` so a generated draft
 * survives the activity, navigation, and process death that previously discarded it. The JSON meta
 * document is the completeness marker: the image bytes are written first, the meta last, so a
 * crash between the two leaves a prompt-only draft instead of a preview without its prompt.
 *
 * Drafts are deliberately excluded from backups — they are unbilled-choice previews, not library
 * content; applying or discarding deletes the files.
 */
internal class AiCoverDraftStore(
    root: File,
    private val safeName: (String) -> String,
) {
    private val dir = File(root, "ai_cover_drafts")
    private val gson = Gson()

    private data class DraftMeta(
        val prompt: String = "",
        val mediaType: String? = null,
    )

    @Synchronized
    fun savePrompt(
        storyId: String,
        prompt: String,
    ) {
        // A fresh prompt invalidates any preview painted from the previous one.
        findImage(storyId)?.delete()
        writeMeta(storyId, DraftMeta(prompt = prompt, mediaType = null))
    }

    @Synchronized
    fun saveImage(
        storyId: String,
        draft: AiCoverDraft,
    ) {
        val file = File(dir, "${safeName(storyId)}.${AiCoverPlanning.coverFileExtension(draft.mediaType)}")
        val previous = findImage(storyId)
        AtomicFileWrites.writeBytes(file, draft.bytes)
        writeMeta(storyId, DraftMeta(prompt = draft.prompt, mediaType = draft.mediaType))
        // At most one image per story, whatever extension the model produced this time.
        previous?.takeIf { it != file }?.delete()
    }

    /** The story's persisted draft, or null when there is none. Unreadable files degrade to a prompt-only record. */
    @Synchronized
    fun load(storyId: String): AiCoverDraftRecord? {
        val meta =
            runCatching {
                metaFile(storyId).takeIf { it.isFile }?.let { gson.fromJson(it.readText(), DraftMeta::class.java) }
            }.getOrNull() ?: return null
        if (meta.prompt.isBlank()) return null
        val image = findImage(storyId) ?: return AiCoverDraftRecord.PromptOnly(meta.prompt)
        val bytes = runCatching { image.readBytes() }.getOrNull() ?: return AiCoverDraftRecord.PromptOnly(meta.prompt)
        return AiCoverDraftRecord.Image(AiCoverDraft(prompt = meta.prompt, bytes = bytes, mediaType = meta.mediaType))
    }

    /** Removes the story's draft entirely; a no-op if there is none. */
    @Synchronized
    fun delete(storyId: String) {
        metaFile(storyId).delete()
        findImage(storyId)?.delete()
    }

    private fun writeMeta(
        storyId: String,
        meta: DraftMeta,
    ) {
        dir.mkdirs()
        AtomicFileWrites.writeBytes(metaFile(storyId), gson.toJson(meta).toByteArray(Charsets.UTF_8))
    }

    private fun metaFile(storyId: String): File = File(dir, "${safeName(storyId)}.json")

    private fun findImage(storyId: String): File? =
        dir.listFiles()?.firstOrNull { it.isFile && it.nameWithoutExtension == safeName(storyId) && it.extension != "json" }
}
