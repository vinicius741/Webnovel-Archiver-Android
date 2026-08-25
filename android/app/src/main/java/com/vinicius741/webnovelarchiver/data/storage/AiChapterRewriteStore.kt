package com.vinicius741.webnovelarchiver.data.storage

import com.google.gson.Gson
import com.vinicius741.webnovelarchiver.domain.model.AppliedChapterRewrite
import com.vinicius741.webnovelarchiver.domain.model.ChapterRewriteDraftRecord
import com.vinicius741.webnovelarchiver.domain.model.ChapterRewriteManifestModel
import java.io.File

/** One story's applied-rewrite backup payload for a full backup. */
data class StoryRewriteBackup(
    val storyId: String,
    /** Backup path of the per-story manifest: `chapter_rewrites/<dir>/manifest.json`. */
    val manifestPath: String,
    /** Serialized manifest with in-flight drafts stripped, written at [manifestPath]. */
    val manifestJson: String,
    /** Backup path → on-disk file for each applied rewrite's `applied.html`. */
    val appliedFiles: List<Pair<String, File>>,
)

/**
 * Owns the chapter-rewrite variants under `chapter_rewrites/<safeStoryId>/`:
 *
 *  - `manifest.json` — the atomic, versioned per-story index (the completeness marker: content
 *    files are written first, the manifest last, so a crash between the two degrades to "no
 *    rewrite" instead of a record pointing at missing files).
 *  - `<safeChapterId>-<hash>/draft.html` — a complete preview draft; never resolved by Reader/TTS.
 *  - `<safeChapterId>-<hash>/applied.html` — the current polished version for a chapter.
 *
 * The downloaded source chapter files are never touched; applying changes only which local variant
 * the content resolver serves. Drafts are deliberately excluded from backups (unbilled-choice
 * previews); applied files and manifests are included.
 */
@Suppress("TooManyFunctions") // Deliberately one store owning the whole feature's file surface.
internal class AiChapterRewriteStore(
    root: File,
    private val safeName: (String) -> String,
) {
    private val dir = File(root, DIRECTORY_NAME)
    private val gson = Gson()

    /** The story's manifest; empty (never null) when absent or unreadable. */
    @Synchronized
    fun manifest(storyId: String): ChapterRewriteManifestModel =
        runCatching {
            manifestFile(storyId).takeIf { it.isFile }?.let { gson.fromJson(it.readText(), ChapterRewriteManifestModel::class.java) }
        }.getOrNull()?.takeIf { it.format == FORMAT } ?: ChapterRewriteManifestModel()

    @Synchronized
    fun appliedRecord(
        storyId: String,
        chapterId: String,
    ): AppliedChapterRewrite? = manifest(storyId).applied[chapterId]?.takeIf { it.fileStem.isNotBlank() }

    @Synchronized
    fun draftRecord(
        storyId: String,
        chapterId: String,
    ): ChapterRewriteDraftRecord? = manifest(storyId).drafts[chapterId]?.takeIf { it.fileStem.isNotBlank() }

    /** The chapter's active applied HTML, or null when there is no active rewrite. */
    @Synchronized
    fun appliedHtml(
        storyId: String,
        chapterId: String,
    ): String? {
        val record = appliedRecord(storyId, chapterId) ?: return null
        if (!record.active) return null
        return readHtml(appliedFile(storyId, record.fileStem))
    }

    @Synchronized
    fun draftHtml(
        storyId: String,
        chapterId: String,
    ): String? {
        val record = draftRecord(storyId, chapterId) ?: return null
        return readHtml(draftFile(storyId, record.fileStem))
    }

    /** The applied file for a record regardless of its active flag — the compare/preview path. */
    @Synchronized
    fun appliedHtmlForRecord(record: AppliedChapterRewrite): String? = readHtml(appliedFile(record.storyId, record.fileStem))

    /** Writes the draft content, then the manifest — persist-before-announce across a crash. */
    @Synchronized
    fun saveDraft(
        storyId: String,
        record: ChapterRewriteDraftRecord,
        draftHtml: String,
    ) {
        val stem = fileStem(record.chapterId)
        chapterDir(storyId, stem).mkdirs()
        AtomicFileWrites.writeText(draftFile(storyId, stem), draftHtml)
        writeManifest(storyId) { current ->
            current.copy(drafts = current.drafts + (record.chapterId to record.copy(fileStem = stem)))
        }
    }

    /** Promotes the chapter's draft to the applied variant and marks it active. */
    @Synchronized
    fun applyDraft(
        storyId: String,
        chapterId: String,
    ): AppliedChapterRewrite? {
        val current = manifest(storyId)
        val draft = current.drafts[chapterId] ?: return null
        val html = readHtml(draftFile(storyId, draft.fileStem)) ?: return null
        AtomicFileWrites.writeText(appliedFile(storyId, draft.fileStem), html)
        val applied =
            AppliedChapterRewrite(
                storyId = draft.storyId,
                chapterId = draft.chapterId,
                chapterTitle = draft.chapterTitle,
                sourceSha256 = draft.sourceSha256,
                appliedAt = System.currentTimeMillis(),
                createdAt = draft.createdAt,
                model = draft.model,
                verifierModel = draft.verifierModel,
                promptVersion = draft.promptVersion,
                strength = draft.strength,
                operationId = draft.operationId,
                costUsd = draft.costUsd,
                verification = draft.verification,
                mergedBlocks = draft.mergedBlocks,
                providerTier = draft.providerTier,
                active = true,
                fileStem = draft.fileStem,
                cadence = draft.cadence,
            )
        writeManifest(storyId) { latest ->
            latest.copy(
                applied = latest.applied + (chapterId to applied),
                drafts = latest.drafts - chapterId,
            )
        }
        draftFile(storyId, draft.fileStem).delete()
        return applied
    }

    /** Removes the chapter's preview draft; the applied variant, if any, is untouched. */
    @Synchronized
    fun discardDraft(
        storyId: String,
        chapterId: String,
    ) {
        val current = manifest(storyId)
        val draft = current.drafts[chapterId] ?: return
        writeManifest(storyId) { latest -> latest.copy(drafts = latest.drafts - chapterId) }
        draftFile(storyId, draft.fileStem).delete()
    }

    /** Flips which variant the content resolver serves; the applied file is kept either way. */
    @Synchronized
    fun setActive(
        storyId: String,
        chapterId: String,
        active: Boolean,
    ): AppliedChapterRewrite? {
        val record = manifest(storyId).applied[chapterId] ?: return null
        val updated = record.copy(active = active)
        writeManifest(storyId) { current ->
            current.copy(applied = current.applied + (chapterId to updated))
        }
        return updated
    }

    /** Removes the chapter's rewrite entirely (draft, applied file, and records). */
    @Synchronized
    fun removeRewrite(
        storyId: String,
        chapterId: String,
    ) {
        val current = manifest(storyId)
        val stems = listOfNotNull(current.applied[chapterId]?.fileStem, current.drafts[chapterId]?.fileStem).distinct()
        writeManifest(storyId) { latest ->
            latest.copy(applied = latest.applied - chapterId, drafts = latest.drafts - chapterId)
        }
        stems.forEach { stem -> chapterDir(storyId, stem).deleteRecursively() }
    }

    /** Removes the story's rewrites entirely; a no-op if there are none. */
    @Synchronized
    fun delete(storyId: String) {
        File(dir, safeName(storyId)).deleteRecursively()
    }

    /**
     * Collects one story's applied rewrites for a full backup, keyed by the RAW story id — callers
     * enumerate the library, because directory names are `safeName` output and cannot round-trip
     * unicode ids (distinct raw ids can even collide on one safe name). Returns null when there is
     * nothing durable to back up: drafts-only staging, or applied records with no files on disk.
     */
    @Synchronized
    fun backupPayloadForStory(storyId: String): StoryRewriteBackup? {
        val current = manifest(storyId)
        val present =
            current.applied.values.mapNotNull { record ->
                appliedFile(storyId, record.fileStem).takeIf { it.isFile }?.let { record to it }
            }
        if (present.isEmpty()) return null
        val storyDir = safeName(storyId)
        return StoryRewriteBackup(
            storyId = storyId,
            manifestPath = "$DIRECTORY_NAME/$storyDir/manifest.json",
            manifestJson =
                gson.toJson(
                    current.copy(
                        drafts = emptyMap(),
                        applied = present.associate { (record, _) -> record.chapterId to record },
                    ),
                ),
            appliedFiles = present.map { (_, file) -> "$DIRECTORY_NAME/${file.toRelativeString(dir)}" to file },
        )
    }

    fun fileStem(chapterId: String): String = "${safeName(chapterId).take(80)}-${Integer.toHexString(chapterId.hashCode())}"

    private fun writeManifest(
        storyId: String,
        update: (ChapterRewriteManifestModel) -> ChapterRewriteManifestModel,
    ) {
        dir.mkdirs()
        AtomicFileWrites.writeBytes(
            manifestFile(storyId),
            gson.toJson(update(manifest(storyId))).toByteArray(Charsets.UTF_8),
        )
    }

    private fun manifestFile(storyId: String): File = File(File(dir, safeName(storyId)), "manifest.json")

    private fun chapterDir(
        storyId: String,
        stem: String,
    ): File = File(File(dir, safeName(storyId)), stem)

    private fun draftFile(
        storyId: String,
        stem: String,
    ): File = File(chapterDir(storyId, stem), "draft.html")

    private fun appliedFile(
        storyId: String,
        stem: String,
    ): File = File(chapterDir(storyId, stem), "applied.html")

    private fun readHtml(file: File): String? = runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull()

    internal companion object {
        /** Directory name shared by the on-disk layout and full-backup zip paths. */
        const val DIRECTORY_NAME = "chapter_rewrites"

        const val FORMAT = "webnovel_archiver.chapter_rewrites"
    }
}
