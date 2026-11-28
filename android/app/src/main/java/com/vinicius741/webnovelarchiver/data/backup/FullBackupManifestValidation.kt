package com.vinicius741.webnovelarchiver.data.backup

import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.Story

/** Manifest data needed by restore after the untrusted manifest has passed validation. */
data class FullBackupManifest(
    val version: Int,
    val library: List<Story>,
    /** The raw `config` object; [com.vinicius741.webnovelarchiver.data.storage.RestoreStagingWriter] decodes each key typed. */
    val config: JsonObject,
    /** Version 1: the manifest's `chapterFiles`. Version 2: derived from [library] (see [FullBackupManifestValidation]). */
    val chapterFiles: List<RestoredChapterFileIndex>,
    val metricFiles: List<RestoredMetricFileIndex>,
    val coverFiles: List<RestoredCoverFileIndex>,
    val rewriteFiles: List<RestoredRewriteFileIndex>,
)

/**
 * One entry of a manifest file index as read, before validation. A field is null when the key is
 * absent or does not hold a JSON string.
 */
data class RawIndexEntry(
    val storyId: String? = null,
    val path: String? = null,
    val chapterId: String? = null,
    val kind: String? = null,
)

/**
 * A manifest file index as read. [entries] is null when the key held something other than an array;
 * a null element is an array item that was not an object. [size] counts every array item, while
 * [entries] stops growing once it holds more than any limit allows ([FullBackupManifestReader]).
 */
class RawIndex(
    val entries: List<RawIndexEntry?>?,
    val size: Int,
)

/**
 * The `library` array as read. Stories are decoded typed one at a time; [malformed] records an
 * item that was not an object with a string `id`, and [decodeError] the first story that did not
 * decode. Decoding stops (but counting continues) past [BackupInputLimits.MAX_STORIES].
 */
class RawLibrary(
    /** The `id` of every item that is an object with a string `id`, in order (decoded or not). */
    val ids: List<String>,
    val stories: List<Story>,
    val size: Int,
    val malformed: Boolean,
    val decodeError: JsonParseException?,
)

/**
 * A full-backup manifest as streamed from `manifest.json`, before any validation. A property is
 * null when its key is absent (or, for [format], [version] and [config], has the wrong JSON type).
 */
class RawFullBackupManifest(
    val format: String? = null,
    val version: Int? = null,
    /** Null when `library` is absent or not an array. */
    val library: RawLibrary? = null,
    val config: JsonObject? = null,
    val chapterFiles: RawIndex? = null,
    val metricFiles: RawIndex? = null,
    val coverFiles: RawIndex? = null,
    val rewriteFiles: RawIndex? = null,
    val missingContent: RawIndex? = null,
)

/**
 * Validates a streamed manifest and derives the typed restore index. Version 1 lists every chapter
 * file in `chapterFiles`. Version 2 drops that list: the chapter files are exactly the library's
 * downloaded chapters at [FullBackupPaths.chapterPath], minus the chapters listed in
 * `missingContent`, which is how [com.vinicius741.webnovelarchiver.data.storage.BackupExporter]
 * builds the ZIP. Both versions then pass the same chapter-index checks and the same ZIP-index
 * comparison in the restorer.
 */
object FullBackupManifestValidation {
    const val FORMAT = "webnovel-archiver-full-backup"

    /** Manifest version that lists `chapterFiles` explicitly. Restore keeps reading it. */
    const val VERSION_WITH_CHAPTER_INDEX = 1

    /** Current manifest version: compact JSON, chapter files derived from the library. */
    const val CURRENT_VERSION = 2
    const val MISSING_MANIFEST_MESSAGE = "Invalid full backup: missing manifest"

    fun validate(manifest: RawFullBackupManifest): String? = validated(manifest).second

    /**
     * Converts only a manifest that passes [validate]. Later restore stages receive typed entries
     * and do not reparse untrusted values.
     */
    fun parseValidated(manifest: RawFullBackupManifest): FullBackupManifest {
        val (chapterFiles, error) = validated(manifest)
        error?.let(::error)
        manifest.library?.decodeError?.let { throw it }
        return FullBackupManifest(
            version = checkNotNull(manifest.version),
            library = checkNotNull(manifest.library).stories,
            config = checkNotNull(manifest.config),
            chapterFiles = checkNotNull(chapterFiles),
            metricFiles = manifest.metricFiles.storyPaths(::RestoredMetricFileIndex),
            coverFiles = manifest.coverFiles.storyPaths(::RestoredCoverFileIndex),
            rewriteFiles = manifest.rewriteFiles.storyPaths(::RestoredRewriteFileIndex),
        )
    }

    /** The chapter index (when valid) and the first validation error, in the order users see them. */
    private fun validated(manifest: RawFullBackupManifest): Pair<List<RestoredChapterFileIndex>?, String?> {
        val version = manifest.version
        headerError(manifest, version)?.let { return null to it }
        val library = checkNotNull(manifest.library)
        if (library.size > BackupInputLimits.MAX_STORIES) return null to "Invalid full backup: too many stories"
        if (library.malformed) return null to "Invalid full backup: malformed story data"
        val ids = library.ids
        if (ids.any(String::isBlank)) return null to "Invalid full backup: malformed story data"
        val idSet = ids.toSet()
        if (idSet.size != ids.size) return null to "Invalid full backup: duplicate story IDs"
        val chapterFiles =
            if (version == VERSION_WITH_CHAPTER_INDEX) {
                listedChapterFiles(checkNotNull(manifest.chapterFiles), idSet)
            } else {
                derivedChapterFiles(library.stories, manifest.missingContent, idSet)
            }
        val chapterError =
            chapterFiles.second
                ?: chapterIndexError(checkNotNull(chapterFiles.first))
                ?: validateMetricFiles(manifest.metricFiles, idSet)
                ?: validateCoverFiles(manifest.coverFiles, idSet)
                ?: validateRewriteFiles(manifest.rewriteFiles, idSet)
        return if (chapterError == null) chapterFiles.first to null else null to chapterError
    }

    private fun headerError(
        manifest: RawFullBackupManifest,
        version: Int?,
    ): String? =
        when {
            manifest.format != FORMAT -> "Invalid full backup: unsupported format"
            version == null -> "Invalid full backup: missing version"
            version != VERSION_WITH_CHAPTER_INDEX && version != CURRENT_VERSION -> "Invalid full backup: unsupported version $version"
            manifest.library == null -> "Invalid full backup: missing library"
            version == VERSION_WITH_CHAPTER_INDEX && manifest.chapterFiles?.entries == null ->
                "Invalid full backup: missing chapter file index"
            // Version 2 derives the index; a listed one would be a second source of truth.
            version == CURRENT_VERSION && manifest.chapterFiles != null -> "Invalid full backup: unexpected chapter file index"
            manifest.config == null || manifest.config.get("tabs")?.isJsonArray != true -> "Invalid full backup: missing configuration"
            else -> null
        }

    private fun listedChapterFiles(
        index: RawIndex,
        ids: Set<String>,
    ): Pair<List<RestoredChapterFileIndex>?, String?> {
        if (index.size > BackupInputLimits.MAX_CHAPTER_FILES) return null to "Invalid full backup: too many chapter files"
        val entries = checkNotNull(index.entries)
        val malformed =
            entries.any { entry ->
                entry == null || entry.storyId.isNullOrBlank() || entry.storyId !in ids || entry.chapterId.isNullOrBlank()
            }
        if (malformed) return null to "Invalid full backup: malformed chapter file index"
        return entries.mapNotNull { entry ->
            val storyId = entry?.storyId ?: return@mapNotNull null
            val chapterId = entry.chapterId ?: return@mapNotNull null
            RestoredChapterFileIndex(storyId, chapterId, entry.path.orEmpty())
        } to null
    }

    /**
     * Rebuilds the exporter's chapter list: every downloaded chapter at its library position, except
     * the chapters `missingContent` reports, whose content was absent at export time.
     */
    private fun derivedChapterFiles(
        stories: List<Story>,
        missingContent: RawIndex?,
        ids: Set<String>,
    ): Pair<List<RestoredChapterFileIndex>?, String?> {
        val missing = missingChapterKeys(missingContent, ids) ?: return null to "Invalid full backup: malformed missing content index"
        val files = mutableListOf<RestoredChapterFileIndex>()
        stories.forEach { story ->
            // Gson can leave a non-null Kotlin property null when the JSON says so.
            val chapters: List<Chapter?>? = story.chapters
            chapters.orEmpty().forEachIndexed { index, chapter ->
                if (chapter?.downloaded != true) return@forEachIndexed
                val chapterId: String? = chapter.id
                if (chapterId.isNullOrBlank()) return null to "Invalid full backup: malformed chapter file index"
                if (Pair(story.id, chapterId) in missing) return@forEachIndexed
                if (files.size >= BackupInputLimits.MAX_CHAPTER_FILES) return null to "Invalid full backup: too many chapter files"
                files += RestoredChapterFileIndex(story.id, chapterId, FullBackupPaths.chapterPath(story.id, chapterId, index))
            }
        }
        return files to null
    }

    /** (storyId, chapterId) of every chapter `missingContent` lists, or null when the list is malformed. */
    private fun missingChapterKeys(
        missingContent: RawIndex?,
        ids: Set<String>,
    ): Set<Pair<String, String>>? {
        missingContent ?: return emptySet()
        if (missingContent.size > BackupInputLimits.MAX_CHAPTER_FILES) return null
        val entries = missingContent.entries ?: return null
        return entries
            .map { entry ->
                entry ?: return null
                if (entry.kind != MISSING_CHAPTER_KIND) return@map null
                val storyId = entry.storyId
                val chapterId = entry.chapterId
                if (storyId.isNullOrBlank() || storyId !in ids || chapterId.isNullOrBlank()) return null
                Pair(storyId, chapterId)
            }.filterNotNullTo(mutableSetOf())
    }

    /** Checks shared by listed (v1) and derived (v2) chapter indexes. */
    private fun chapterIndexError(chapterFiles: List<RestoredChapterFileIndex>): String? {
        if (chapterFiles.any { it.path.isBlank() || !BackupInputLimits.isAllowedFullBackupEntry(it.path, directory = false) }) {
            return "Invalid full backup: malformed chapter file index"
        }
        if (chapterFiles.mapTo(HashSet()) { it.path }.size != chapterFiles.size) return "Invalid full backup: duplicate chapter paths"
        if (chapterFiles.mapTo(HashSet()) { Pair(it.storyId, it.chapterId) }.size != chapterFiles.size) {
            return "Invalid full backup: duplicate chapter entries"
        }
        return null
    }

    private fun RawIndex?.validEntries(): List<RawIndexEntry> = this?.entries.orEmpty().filterNotNull()

    /** Typed `{storyId, path}` entries of an index that already passed validation. */
    private fun <T> RawIndex?.storyPaths(entry: (storyId: String, path: String) -> T): List<T> =
        validEntries().mapNotNull { raw -> raw.storyId?.let { storyId -> raw.path?.let { path -> entry(storyId, path) } } }

    /** Shared shape check for the per-story indexes: object entries whose story is in the library. */
    private fun RawIndex.malformedStoryPaths(
        ids: Set<String>,
        allowedPath: (String) -> Boolean,
    ): Boolean =
        checkNotNull(entries).any { entry ->
            entry == null ||
                entry.storyId.isNullOrBlank() ||
                entry.storyId !in ids ||
                entry.path.isNullOrBlank() ||
                !allowedPath(entry.path) ||
                !BackupInputLimits.isAllowedFullBackupEntry(entry.path, directory = false)
        }

    /** metricFiles is optional: backups written before the Trends feature shipped omit it, and a
     *  restore then leaves each story with empty history. When present it must be well-formed. */
    private fun validateMetricFiles(
        metricFiles: RawIndex?,
        ids: Set<String>,
    ): String? {
        if (metricFiles == null) return null
        if (metricFiles.entries == null) return "Invalid full backup: malformed metric file index"
        if (metricFiles.size > ids.size) return "Invalid full backup: too many metric files"
        if (metricFiles.malformedStoryPaths(ids) { true }) return "Invalid full backup: malformed metric file index"
        val entries = metricFiles.validEntries()
        if (entries.mapTo(HashSet()) { it.path }.size != entries.size) return "Invalid full backup: duplicate metric paths"
        if (entries.mapTo(HashSet()) { it.storyId }.size != entries.size) return "Invalid full backup: duplicate metric entries"
        return null
    }

    /** coverFiles is optional, mirroring metricFiles: backups written before AI covers shipped omit
     *  it, and a restore then falls back to each story's source cover URL. */
    private fun validateCoverFiles(
        coverFiles: RawIndex?,
        ids: Set<String>,
    ): String? {
        if (coverFiles == null) return null
        if (coverFiles.entries == null) return "Invalid full backup: malformed cover file index"
        if (coverFiles.size > ids.size) return "Invalid full backup: too many cover files"
        // The exporter only ever records covers under their own covers/ path, and the staged copy
        // relies on that tree — entries pointing elsewhere are rejected even when the general entry
        // allowlist would accept them (e.g. a novels/ chapter).
        if (coverFiles.malformedStoryPaths(ids) { it.startsWith("covers/") }) return "Invalid full backup: malformed cover file index"
        val entries = coverFiles.validEntries()
        if (entries.mapTo(HashSet()) { it.path }.size != entries.size) return "Invalid full backup: duplicate cover paths"
        if (entries.mapTo(HashSet()) { it.storyId }.size != entries.size) return "Invalid full backup: duplicate cover entries"
        return null
    }

    /** rewriteFiles appears only in backups written while the retired Chapter polish feature
     *  existed. It stays validated (many entries per story are legitimate — one applied.html per
     *  polished chapter plus the per-story manifest) so those backups restore; the files are dropped. */
    private fun validateRewriteFiles(
        rewriteFiles: RawIndex?,
        ids: Set<String>,
    ): String? {
        if (rewriteFiles == null) return null
        if (rewriteFiles.entries == null) return "Invalid full backup: malformed rewrite file index"
        if (rewriteFiles.size > BackupInputLimits.MAX_CHAPTER_FILES) return "Invalid full backup: too many rewrite files"
        // The exporter only ever recorded rewrites under their own chapter_rewrites/ tree; entries
        // pointing elsewhere are rejected even when the general entry allowlist would accept them.
        if (rewriteFiles.malformedStoryPaths(ids) { it.startsWith("chapter_rewrites/") }) {
            return "Invalid full backup: malformed rewrite file index"
        }
        val entries = rewriteFiles.validEntries()
        if (entries.mapTo(HashSet()) { it.path }.size != entries.size) return "Invalid full backup: duplicate rewrite paths"
        return null
    }

    /** `missingContent` kind for a downloaded chapter whose content was absent at export. */
    const val MISSING_CHAPTER_KIND = "chapter"
}
