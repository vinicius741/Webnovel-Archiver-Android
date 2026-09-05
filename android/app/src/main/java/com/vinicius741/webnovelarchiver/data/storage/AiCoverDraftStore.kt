package com.vinicius741.webnovelarchiver.data.storage

import com.vinicius741.webnovelarchiver.ai.AiCoverDraft
import com.vinicius741.webnovelarchiver.ai.AiCoverPlanning
import com.vinicius741.webnovelarchiver.domain.model.AiCoverDraftMeta
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
 * Each generation writes its own image file and the meta switches the reference only afterward
 * (R09): replacing a draft can never pair new bytes with the previous generation's prompt or
 * media type. The replaced generation is deleted only after the meta commit.
 *
 * Drafts are deliberately excluded from backups — they are unbilled-choice previews, not library
 * content; applying or discarding deletes the files.
 */
internal class AiCoverDraftStore(
    root: File,
    private val safeName: (String) -> String,
) {
    private val dir = File(root, "ai_cover_drafts")
    private val gson = com.google.gson.Gson()
    private val generationCounter =
        java.util.concurrent.atomic
            .AtomicLong()

    @Synchronized
    fun savePrompt(
        storyId: String,
        prompt: String,
    ) {
        // A fresh prompt invalidates any preview painted from the previous one.
        referencedImage(storyId)?.delete()
        writeMeta(storyId, AiCoverDraftMeta(prompt = prompt, mediaType = null, imageFile = null))
    }

    @Synchronized
    fun saveImage(
        storyId: String,
        draft: AiCoverDraft,
    ) {
        // Generation-specific name: the bytes the current meta references are never overwritten
        // in place (R09).
        val imageFile = generationImageName(storyId, draft.mediaType)
        val previous = referencedImage(storyId)
        AtomicFileWrites.writeBytes(File(dir, imageFile), draft.bytes)
        writeMeta(storyId, AiCoverDraftMeta(prompt = draft.prompt, mediaType = draft.mediaType, imageFile = imageFile))
        // At most one image per story; drop the replaced generation only after the meta commit.
        previous?.takeIf { it.name != imageFile }?.delete()
    }

    /** The story's persisted draft, or null when there is none. Unreadable files degrade to a prompt-only record. */
    @Synchronized
    fun load(storyId: String): AiCoverDraftRecord? {
        val meta = readMeta(storyId) ?: return null
        if (meta.prompt.isBlank()) return null
        val image = referencedImage(storyId) ?: return AiCoverDraftRecord.PromptOnly(meta.prompt)
        val bytes = runCatching { image.readBytes() }.getOrNull() ?: return AiCoverDraftRecord.PromptOnly(meta.prompt)
        return AiCoverDraftRecord.Image(AiCoverDraft(prompt = meta.prompt, bytes = bytes, mediaType = meta.mediaType))
    }

    /** Removes the story's draft entirely; a no-op if there is none. */
    @Synchronized
    fun delete(storyId: String) {
        referencedImage(storyId)?.delete()
        metaFile(storyId).delete()
    }

    /** The image the current meta references, with legacy-name discovery as fallback. */
    private fun referencedImage(storyId: String): File? {
        readMeta(storyId)?.imageFile?.let { name ->
            if (name.isNotBlank() && !name.contains('/') && !name.contains('\\') && !name.contains("..")) {
                File(dir, name).takeIf { it.isFile }?.let { return it }
            }
        }
        return findImage(storyId)
    }

    private fun readMeta(storyId: String): AiCoverDraftMeta? =
        runCatching {
            metaFile(storyId).takeIf { it.isFile }?.let { gson.fromJson(it.readText(), AiCoverDraftMeta::class.java) }
        }.getOrNull()

    private fun writeMeta(
        storyId: String,
        meta: AiCoverDraftMeta,
    ) {
        dir.mkdirs()
        AtomicFileWrites.writeBytes(metaFile(storyId), gson.toJson(meta).toByteArray(Charsets.UTF_8))
    }

    private fun metaFile(storyId: String): File = File(dir, "${safeName(storyId)}.json")

    private fun generationImageName(
        storyId: String,
        mediaType: String?,
    ): String {
        val extension = AiCoverPlanning.coverFileExtension(mediaType)
        val generation = generationCounter.incrementAndGet().toString(16)
        return "${safeName(storyId)}-g$generation.$extension"
    }

    /** Legacy layout discovery: the pre-generation file was `<safeName>.<ext>`. */
    private fun findImage(storyId: String): File? =
        dir.listFiles()?.firstOrNull { it.isFile && it.nameWithoutExtension == safeName(storyId) && it.extension != "json" }
}
