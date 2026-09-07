package com.vinicius741.webnovelarchiver.data.storage

import com.google.gson.Gson
import com.vinicius741.webnovelarchiver.ai.AiCoverDraft
import com.vinicius741.webnovelarchiver.ai.AiCoverPlanning
import com.vinicius741.webnovelarchiver.domain.model.AiCoverDraftMeta
import java.io.File
import java.security.MessageDigest

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
    private val gson = Gson()
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
                val digest = MessageDigest.getInstance("SHA-256")
                applied.inputStream().use { input ->
                    val buffer = ByteArray(8192)
                    var count = input.read(buffer)
                    while (count >= 0) {
                        digest.update(buffer, 0, count)
                        count = input.read(buffer)
                    }
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
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
        val id = MessageDigest.getInstance("SHA-256").digest(draft.bytes).joinToString("") { "%02x".format(it) }
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
