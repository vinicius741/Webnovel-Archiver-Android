package com.vinicius741.webnovelarchiver.data.storage

import com.google.gson.Gson
import com.vinicius741.webnovelarchiver.domain.model.AppliedChapterRewrite
import com.vinicius741.webnovelarchiver.domain.model.ChapterRewriteDraftRecord
import com.vinicius741.webnovelarchiver.domain.model.ChapterRewriteManifestModel
import java.io.File
import java.io.IOException

/** One story's applied-rewrite backup payload for a full backup. */
data class StoryRewriteBackup(
    val storyId: String,
    /** Backup path of the per-story manifest: `chapter_rewrites/<dir>/manifest.json`. */
    val manifestPath: String,
    /** Serialized manifest with in-flight drafts stripped, written at [manifestPath]. */
    val manifestJson: String,
    /** Backup path → on-disk file for each applied rewrite's content file. */
    val appliedFiles: List<Pair<String, File>>,
    /** Applied records whose content file was missing at export time (R10 reporting). */
    val missingAppliedCount: Int = 0,
)

/**
 * Owns the chapter-rewrite variants under `chapter_rewrites/<safeStoryId>/`:
 *
 *  - `manifest.json` — the atomic, versioned per-story index (the completeness marker: content
 *    files are written first, the manifest last, so a crash between the two degrades to "no
 *    rewrite" instead of a record pointing at missing files).
 *  - `<safeChapterId>-<hash>/draft-<gen>.html` — a complete preview draft; never resolved by
 *    Reader/TTS. The generation suffix keeps a re-generated draft's bytes from pairing with the
 *    previous generation's metadata (R09).
 *  - `<safeChapterId>-<hash>/applied-<gen>.html` — the current polished version for a chapter,
 *    named after the operation that produced it; the manifest's [contentFile] reference is only
 *    switched after the new bytes are durable, and unreferenced generations are removed after
 *    the metadata commit.
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

    /**
     * Per-story manifest cache (R26): reads serve the last parsed snapshot keyed to the file's
     * (mtime, length) stamp, so out-of-band edits are still detected with one stat instead of a
     * re-parse. Store writes drop the entry; [invalidateAll] clears wholesale after restores.
     */
    private data class ManifestCacheEntry(
        val lastModified: Long,
        val length: Long,
        val manifest: ChapterRewriteManifestModel,
    )

    private val manifestCache = java.util.concurrent.ConcurrentHashMap<String, ManifestCacheEntry>()

    /** The story's manifest; empty (never null) when absent or fenced. */
    @Synchronized
    fun manifest(storyId: String): ChapterRewriteManifestModel =
        when (val read = manifestRead(storyId)) {
            is RewriteManifestRead.Ok -> read.manifest
            RewriteManifestRead.Absent -> ChapterRewriteManifestModel()
            is RewriteManifestRead.Fenced -> ChapterRewriteManifestModel()
        }

    /** Typed manifest health for UI recoverable states (R08) and one-snapshot reads (R26). */
    @Synchronized
    fun manifestRead(storyId: String): RewriteManifestRead {
        val file = manifestFile(storyId)
        if (!file.isFile) {
            manifestCache.remove(storyId)
            return RewriteManifestRead.Absent
        }
        val stamp = file.lastModified()
        val length = file.length()
        manifestCache[storyId]?.takeIf { it.lastModified == stamp && it.length == length }?.let {
            return RewriteManifestRead.Ok(it.manifest)
        }
        val text =
            try {
                file.readText()
            } catch (error: IOException) {
                return RewriteManifestRead.Fenced(RewriteManifestRead.Fenced.Reason.IoFailure, error.message ?: "I/O failure")
            }
        val parsed =
            try {
                gson.fromJson(text, ChapterRewriteManifestModel::class.java)
            } catch (
                // Gson surfaces malformed documents through several unchecked exception types;
                // every one of them means "unreadable document", which is the handled outcome.
                @Suppress("TooGenericExceptionCaught") error: Exception,
            ) {
                DurableJson.quarantineCorrupt(file, error, reason = "parse")
                manifestCache.remove(storyId)
                return RewriteManifestRead.Fenced(RewriteManifestRead.Fenced.Reason.Corrupt, "Manifest JSON was malformed and quarantined")
            } ?: return RewriteManifestRead.Fenced(RewriteManifestRead.Fenced.Reason.Corrupt, "Manifest JSON was empty")
        if (parsed.format != FORMAT) {
            return RewriteManifestRead.Fenced(RewriteManifestRead.Fenced.Reason.Corrupt, "Unexpected manifest format '${parsed.format}'")
        }
        if (parsed.version > SUPPORTED_VERSION) {
            return RewriteManifestRead.Fenced(
                RewriteManifestRead.Fenced.Reason.UnsupportedVersion,
                "Manifest version ${parsed.version} is newer than supported version $SUPPORTED_VERSION",
            )
        }
        // Gson can inject explicit nulls past Kotlin's non-null map declarations; re-read the
        // fields through a nullable view so those documents coerce instead of crashing callers.
        val applied = parsed.applied as Map<String, AppliedChapterRewrite>? ?: emptyMap()
        val drafts = parsed.drafts as Map<String, ChapterRewriteDraftRecord>? ?: emptyMap()
        val normalized = parsed.copy(applied = applied, drafts = drafts)
        manifestCache[storyId] = ManifestCacheEntry(stamp, length, normalized)
        return RewriteManifestRead.Ok(normalized)
    }

    /** Drops every cached manifest; call after a restore replaces the on-disk tree (R26). */
    @Synchronized
    fun invalidateAll() {
        manifestCache.clear()
    }

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
        return readHtml(appliedContentFile(storyId, record))
    }

    @Synchronized
    fun draftHtml(
        storyId: String,
        chapterId: String,
    ): String? {
        val record = draftRecord(storyId, chapterId) ?: return null
        return readHtml(draftContentFile(storyId, record))
    }

    /** The applied file for a record regardless of its active flag — the compare/preview path. */
    @Synchronized
    fun appliedHtmlForRecord(record: AppliedChapterRewrite): String? = readHtml(appliedContentFile(record.storyId, record))

    /** Writes the draft content, then the manifest — persist-before-announce across a crash. */
    @Synchronized
    fun saveDraft(
        storyId: String,
        record: ChapterRewriteDraftRecord,
        draftHtml: String,
    ) {
        val stem = fileStem(record.chapterId)
        // Generation-specific filename: a regeneration never overwrites the bytes the current
        // manifest still references (R09).
        val contentFile = draftContentName(record.operationId)
        val chapterDir = chapterDir(storyId, stem).apply { mkdirs() }
        AtomicFileWrites.writeText(File(chapterDir, contentFile), draftHtml)
        writeManifest(storyId) { current ->
            current.copy(drafts = current.drafts + (record.chapterId to record.copy(fileStem = stem, contentFile = contentFile)))
        }
        removeUnreferencedGenerationFiles(storyId, stem)
    }

    /** Promotes the chapter's draft to the applied variant and marks it active. */
    @Synchronized
    fun applyDraft(
        storyId: String,
        chapterId: String,
    ): AppliedChapterRewrite? {
        val current = manifest(storyId)
        val draft = current.drafts[chapterId] ?: return null
        val html = readHtml(draftContentFile(storyId, draft)) ?: return null
        val appliedContentFile = appliedContentName(draft.operationId)
        AtomicFileWrites.writeText(File(chapterDir(storyId, draft.fileStem), appliedContentFile), html)
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
                contentFile = appliedContentFile,
                cadence = draft.cadence,
            )
        writeManifest(storyId) { latest ->
            latest.copy(
                applied = latest.applied + (chapterId to applied),
                drafts = latest.drafts - chapterId,
            )
        }
        removeUnreferencedGenerationFiles(storyId, draft.fileStem)
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
        // The applied variant lives in the same stem directory; only drop what no record
        // references anymore (the draft's generation file), never the directory itself.
        removeUnreferencedGenerationFiles(storyId, draft.fileStem)
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
        manifestCache.remove(storyId)
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
                appliedContentFile(storyId, record).takeIf { it.isFile }?.let { record to it }
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
            missingAppliedCount = current.applied.size - present.size,
        )
    }

    fun fileStem(chapterId: String): String = "${safeName(chapterId).take(80)}-${Integer.toHexString(chapterId.hashCode())}"

    private fun writeManifest(
        storyId: String,
        update: (ChapterRewriteManifestModel) -> ChapterRewriteManifestModel,
    ) {
        val base =
            when (val read = manifestRead(storyId)) {
                is RewriteManifestRead.Ok -> read.manifest
                RewriteManifestRead.Absent -> ChapterRewriteManifestModel()
                is RewriteManifestRead.Fenced ->
                    // R08: never replace a document this process cannot read — the write would
                    // silently drop every applied record it still lists.
                    error("Rewrite manifest for story $storyId is fenced (${read.reason}: ${read.detail}); writes are blocked")
            }
        dir.mkdirs()
        AtomicFileWrites.writeBytes(
            manifestFile(storyId),
            gson.toJson(update(base)).toByteArray(Charsets.UTF_8),
        )
        // Drop the cached entry; the next read re-stats the file and re-populates (R26).
        manifestCache.remove(storyId)
    }

    /**
     * After the manifest commit, drops content files in [stem]'s directory that no record
     * references anymore (R09: the previous generation survives until the metadata commit).
     */
    private fun removeUnreferencedGenerationFiles(
        storyId: String,
        stem: String,
    ) {
        val current = manifest(storyId)
        val referenced =
            buildSet {
                current.applied.values
                    .filter { it.fileStem == stem }
                    .forEach { add(appliedContentFile(storyId, it).name) }
                current.drafts.values
                    .filter { it.fileStem == stem }
                    .forEach { add(draftContentFile(storyId, it).name) }
            }
        chapterDir(storyId, stem)
            .listFiles()
            ?.filter { it.isFile && it.name !in referenced }
            ?.forEach { file -> runCatching { file.delete() } }
    }

    private fun manifestFile(storyId: String): File = File(File(dir, safeName(storyId)), "manifest.json")

    private fun chapterDir(
        storyId: String,
        stem: String,
    ): File = File(File(dir, safeName(storyId)), stem)

    private fun draftContentFile(
        storyId: String,
        record: ChapterRewriteDraftRecord,
    ): File = File(chapterDir(storyId, record.fileStem), record.contentFile ?: LEGACY_DRAFT_NAME)

    private fun appliedContentFile(
        storyId: String,
        record: AppliedChapterRewrite,
    ): File = File(chapterDir(storyId, record.fileStem), record.contentFile ?: LEGACY_APPLIED_NAME)

    private fun draftContentName(operationId: String): String = "draft-${generationSuffix(operationId)}.html"

    private fun appliedContentName(operationId: String): String = "applied-${generationSuffix(operationId)}.html"

    private fun generationSuffix(operationId: String): String =
        operationId.takeIf { it.isNotBlank() }?.let { safeName(it).take(24) } ?: java.lang.Integer.toHexString(operationId.hashCode())

    private fun readHtml(file: File): String? = runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull()

    internal companion object {
        /** Directory name shared by the on-disk layout and full-backup zip paths. */
        const val DIRECTORY_NAME = "chapter_rewrites"

        const val FORMAT = "webnovel_archiver.chapter_rewrites"

        const val SUPPORTED_VERSION = 1

        const val LEGACY_DRAFT_NAME = "draft.html"

        const val LEGACY_APPLIED_NAME = "applied.html"
    }
}
