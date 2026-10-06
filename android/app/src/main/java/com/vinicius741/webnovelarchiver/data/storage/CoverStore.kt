package com.vinicius741.webnovelarchiver.data.storage

import com.vinicius741.webnovelarchiver.ai.AiCoverDraft
import com.vinicius741.webnovelarchiver.ai.AiCoverPlanning
import com.vinicius741.webnovelarchiver.domain.model.AiCoverDraftMeta
import com.vinicius741.webnovelarchiver.domain.sha256Hex
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
 * Owns all AI cover images for the library root: the applied cover (`covers/`, one current file
 * per story), the pending preview drafts (`ai_cover_drafts/`), and the local experiment history
 * (`ai_cover_versions/`). All three stay on the same on-disk layout previous app versions wrote,
 * so existing libraries and full backups keep loading.
 *
 * Draft lifecycle: the JSON meta document is the completeness marker — the image bytes are
 * written first, the meta last, so a crash between the two leaves a prompt-only draft instead of
 * a preview without its prompt. Each generation writes its own image file and the meta switches
 * the reference only afterward: replacing a draft can never pair new bytes with the previous
 * generation's prompt or media type, and the replaced generation is deleted only after the meta
 * commit. Drafts and experiment history are local to this device and excluded from backups;
 * closing or applying a preview keeps its image and prompt in version history.
 */
@Suppress("TooManyFunctions")
internal class CoverStore(
    root: File,
    private val safeName: (String) -> String,
) {
    /** Immutable retained generations; drafts become versions on apply/discard/replace. */
    val versions = AiCoverVersionStore(root, safeName)

    // Created on first write, not construction: constructing a store must not touch the filesystem.
    private val appliedDir = File(root, "covers")
    private val draftsDir = File(root, "ai_cover_drafts")
    private val gson = SharedGson.plain

    /** Recreates the applied-cover directory after a full-backup restore swapped the storage root. */
    fun ensureAppliedDirectory() {
        appliedDir.mkdirs()
    }

    /**
     * Atomically writes [storyId]'s applied cover and returns the file. A cover previously saved
     * under a different extension is removed so at most one applied cover per story is ever
     * current. The caller records the returned path (relativized against the storage root) on the
     * story.
     */
    @Synchronized
    fun saveApplied(
        storyId: String,
        bytes: ByteArray,
        extension: String,
    ): File {
        val file = File(appliedDir, "${safeName(storyId)}.$extension")
        findApplied(storyId)?.takeIf { it != file }?.delete()
        AtomicFileWrites.writeBytes(file, bytes)
        return file
    }

    /** The story's applied cover file, whatever extension it was saved with; null when there is none. */
    @Synchronized
    fun findApplied(storyId: String): File? =
        appliedDir.listFiles()?.firstOrNull { it.isFile && it.nameWithoutExtension == safeName(storyId) }

    /** Removes the story's applied cover file; a no-op if there is none. */
    @Synchronized
    fun deleteApplied(storyId: String) {
        findApplied(storyId)?.delete()
    }

    @Synchronized
    fun savePrompt(
        storyId: String,
        prompt: String,
    ) {
        (load(storyId) as? AiCoverDraftRecord.Image)?.let { versions.save(storyId, it.draft) }
        // A fresh prompt starts a new experiment; the previous image remains in history.
        val previous = referencedImage(storyId)
        writeMeta(storyId, AiCoverDraftMeta(prompt = prompt, mediaType = null, imageFile = null))
        previous?.delete()
    }

    @Synchronized
    fun saveImage(
        storyId: String,
        draft: AiCoverDraft,
    ) {
        (load(storyId) as? AiCoverDraftRecord.Image)?.let { versions.save(storyId, it.draft) }
        versions.save(storyId, draft)
        // Generation-specific name: the bytes the current meta references are never overwritten
        // in place.
        val imageFile = generationImageName(storyId, draft.mediaType)
        val previous = referencedImage(storyId)
        AtomicFileWrites.writeBytes(File(draftsDir, imageFile), draft.bytes)
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

    /** Closes a working draft, retaining its image unless the novel itself is being deleted. */
    @Synchronized
    fun delete(
        storyId: String,
        keepHistory: Boolean = true,
    ) {
        if (keepHistory) {
            (load(storyId) as? AiCoverDraftRecord.Image)?.let { versions.save(storyId, it.draft) }
        }
        referencedImage(storyId)?.delete()
        metaFile(storyId).delete()
        if (!keepHistory) versions.deleteAll(storyId)
    }

    /** The image the current meta references, with legacy-name discovery as fallback. */
    private fun referencedImage(storyId: String): File? {
        readMeta(storyId)?.imageFile?.let { name ->
            if (name.isNotBlank() && !name.contains('/') && !name.contains('\\') && !name.contains("..")) {
                File(draftsDir, name).takeIf { it.isFile }?.let { return it }
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
        draftsDir.mkdirs()
        AtomicFileWrites.writeBytes(metaFile(storyId), gson.toJson(meta).toByteArray(Charsets.UTF_8))
    }

    private fun metaFile(storyId: String): File = File(draftsDir, "${safeName(storyId)}.json")

    private fun generationImageName(
        storyId: String,
        mediaType: String?,
    ): String {
        val extension = AiCoverPlanning.coverFileExtension(mediaType)
        // Random per save, not per process: a restarted counter would reuse the previous
        // process's filename and overwrite the bytes the current meta still references.
        val generation =
            java.util.UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .take(12)
        return "${safeName(storyId)}-g$generation.$extension"
    }

    /** Legacy layout discovery: the pre-generation file was `<safeName>.<ext>`. */
    private fun findImage(storyId: String): File? =
        draftsDir.listFiles()?.firstOrNull { it.isFile && it.nameWithoutExtension == safeName(storyId) && it.extension != "json" }
}

internal data class AiCoverVersion(
    val id: String,
    val prompt: String,
    val mediaType: String?,
    val image: File,
    val createdAt: Long,
    val isCurrent: Boolean = false,
)

/** Local experiment history. Immutable images commit before metadata; identical bytes retain their original prompt. */
internal class AiCoverVersionStore(
    private val root: File,
    private val safeName: (String) -> String,
) {
    private val gson = SharedGson.plain
    private val fileHashes = linkedMapOf<String, Pair<String, String>>()

    /** Read-only legacy fallback; hashing is streamed and cached by file revision, outside repository transactions. */
    @Synchronized
    fun listForDisplay(
        storyId: String,
        applied: File?,
        active: Boolean,
    ): List<AiCoverVersion> {
        val versions = list(storyId)
        if (applied?.isFile != true) return versions
        val revision = "${applied.lastModified()}:${applied.length()}"
        val cached = fileHashes[applied.absolutePath]
        val id =
            if (cached?.first == revision) {
                cached.second
            } else {
                val hash = sha256Hex(applied)
                fileHashes[applied.absolutePath] = revision to hash
                if (fileHashes.size > 128) fileHashes.remove(fileHashes.keys.first())
                hash
            }
        val all =
            if (versions.any { it.id == id }) {
                versions
            } else {
                val mediaType =
                    when (applied.extension.lowercase()) {
                        "jpg", "jpeg" -> "image/jpeg"
                        "webp" -> "image/webp"
                        else -> "image/png"
                    }
                versions + AiCoverVersion(id, "", mediaType, applied, applied.lastModified())
            }
        return all.map { it.copy(isCurrent = active && it.id == id) }
    }

    @Synchronized
    fun save(
        storyId: String,
        draft: AiCoverDraft,
    ): String {
        val id = sha256Hex(draft.bytes)
        val dir = directory(storyId)
        val meta = File(dir, "$id.json")
        if (meta.isFile) return id
        val image = File(dir, "$id.${AiCoverPlanning.coverFileExtension(draft.mediaType)}")
        AtomicFileWrites.writeBytes(image, draft.bytes)
        AtomicFileWrites.writeBytes(
            meta,
            gson.toJson(AiCoverDraftMeta(draft.prompt, draft.mediaType, image.name)).toByteArray(Charsets.UTF_8),
        )
        return id
    }

    @Synchronized
    fun list(storyId: String): List<AiCoverVersion> =
        directory(storyId)
            .listFiles()
            .orEmpty()
            .filter { it.extension == "json" }
            .mapNotNull { file ->
                runCatching {
                    val meta = gson.fromJson(file.readText(), AiCoverDraftMeta::class.java)
                    val name = meta.imageFile ?: return@runCatching null
                    if (name != File(name).name || name.contains("..") || name.contains('\\')) return@runCatching null
                    val image = File(file.parentFile, name).takeIf { it.isFile } ?: return@runCatching null
                    AiCoverVersion(file.nameWithoutExtension, meta.prompt, meta.mediaType, image, file.lastModified())
                }.getOrNull()
            }.sortedWith(compareByDescending<AiCoverVersion> { it.createdAt }.thenBy { it.id })

    @Synchronized
    fun deleteAll(storyId: String) {
        directory(storyId).deleteRecursively()
    }

    private fun directory(storyId: String): File = File(File(root, "ai_cover_versions"), safeName(storyId))
}
