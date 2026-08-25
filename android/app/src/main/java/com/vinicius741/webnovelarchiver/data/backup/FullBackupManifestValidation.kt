package com.vinicius741.webnovelarchiver.data.backup

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.vinicius741.webnovelarchiver.domain.model.Story

/** Manifest data needed by restore after the untrusted raw JSON has passed validation. */
data class FullBackupManifest(
    val version: Int,
    val library: List<Story>,
    val config: Map<String, Any>,
    val chapterFiles: List<RestoredChapterFileIndex>,
    val metricFiles: List<RestoredMetricFileIndex>,
    val coverFiles: List<RestoredCoverFileIndex>,
    val rewriteFiles: List<RestoredRewriteFileIndex>,
)

object FullBackupManifestValidation {
    private const val FORMAT = "webnovel-archiver-full-backup"
    const val MISSING_MANIFEST_MESSAGE = "Invalid full backup: missing manifest"

    fun validate(manifest: Map<String, Any>?): String? {
        if (manifest == null || manifest["format"] != FORMAT) {
            return "Invalid full backup: unsupported format"
        }
        val version = BackupInputLimits.exactInt(manifest["version"])
        if (version == null) {
            return "Invalid full backup: missing version"
        }
        if (version != 1) return "Invalid full backup: unsupported version $version"
        if (manifest["library"] !is List<*>) {
            return "Invalid full backup: missing library"
        }
        if (manifest["chapterFiles"] !is List<*>) {
            return "Invalid full backup: missing chapter file index"
        }
        val config = manifest["config"]
        if (config !is Map<*, *> || config["tabs"] !is List<*>) {
            return "Invalid full backup: missing configuration"
        }
        val library = manifest["library"] as List<*>
        if (library.size > BackupInputLimits.MAX_STORIES) return "Invalid full backup: too many stories"
        if (!library.all { story -> story is Map<*, *> && story["id"] is String }) {
            return "Invalid full backup: malformed story data"
        }
        val ids = library.map { (it as Map<*, *>)["id"] as String }
        if (ids.any(String::isBlank)) return "Invalid full backup: malformed story data"
        if (ids.distinct().size != ids.size) return "Invalid full backup: duplicate story IDs"
        val chapterFiles = manifest["chapterFiles"] as List<*>
        if (chapterFiles.size > BackupInputLimits.MAX_CHAPTER_FILES) {
            return "Invalid full backup: too many chapter files"
        }
        val chapterEntries = chapterFiles.map { it as? Map<*, *> ?: return "Invalid full backup: malformed chapter file index" }
        if (
            chapterEntries.any { entry ->
                val storyId = entry["storyId"] as? String
                val chapterId = entry["chapterId"] as? String
                val path = entry["path"] as? String
                storyId.isNullOrBlank() ||
                    storyId !in ids ||
                    chapterId.isNullOrBlank() ||
                    path.isNullOrBlank() ||
                    !BackupInputLimits.isAllowedFullBackupEntry(path, directory = false)
            }
        ) {
            return "Invalid full backup: malformed chapter file index"
        }
        val paths = chapterEntries.map { it["path"] as String }
        if (paths.distinct().size != paths.size) return "Invalid full backup: duplicate chapter paths"
        val chapterKeys = chapterEntries.map { Pair(it["storyId"] as String, it["chapterId"] as String) }
        if (chapterKeys.distinct().size != chapterKeys.size) {
            return "Invalid full backup: duplicate chapter entries"
        }
        validateMetricFiles(manifest["metricFiles"], ids.toSet())?.let { return it }
        validateCoverFiles(manifest["coverFiles"], ids.toSet())?.let { return it }
        return validateRewriteFiles(manifest["rewriteFiles"], ids.toSet())
    }

    /**
     * Converts only a manifest that has already passed the raw shape and safety checks above.
     * Later restore stages receive typed entries and do not need to reparse untrusted map values.
     */
    fun parseValidated(
        gson: Gson,
        manifest: Map<String, Any>,
    ): FullBackupManifest {
        validate(manifest)?.let(::error)
        val stories: List<Story> =
            gson.fromJson(gson.toJson(manifest["library"]), object : TypeToken<List<Story>>() {}.type)
                ?: error("Invalid full backup: missing library")
        val config: Map<String, Any> =
            gson.fromJson(gson.toJson(manifest["config"]), object : TypeToken<Map<String, Any>>() {}.type)
                ?: error("Invalid full backup: missing configuration")
        val chapterFiles =
            (manifest["chapterFiles"] as List<*>).map { raw ->
                val entry = raw as Map<*, *>
                RestoredChapterFileIndex(
                    storyId = entry.getString("storyId"),
                    chapterId = entry.getString("chapterId"),
                    path = entry.getString("path"),
                )
            }
        val metricFiles =
            (manifest["metricFiles"] as? List<*>).orEmpty().map { raw ->
                val entry = raw as Map<*, *>
                RestoredMetricFileIndex(
                    storyId = entry.getString("storyId"),
                    path = entry.getString("path"),
                )
            }
        val coverFiles =
            (manifest["coverFiles"] as? List<*>).orEmpty().map { raw ->
                val entry = raw as Map<*, *>
                RestoredCoverFileIndex(
                    storyId = entry.getString("storyId"),
                    path = entry.getString("path"),
                )
            }
        val rewriteFiles =
            (manifest["rewriteFiles"] as? List<*>).orEmpty().map { raw ->
                val entry = raw as Map<*, *>
                RestoredRewriteFileIndex(
                    storyId = entry.getString("storyId"),
                    path = entry.getString("path"),
                )
            }
        return FullBackupManifest(
            version = BackupInputLimits.exactInt(manifest["version"]) ?: error("Invalid full backup: missing version"),
            library = stories,
            config = config,
            chapterFiles = chapterFiles,
            metricFiles = metricFiles,
            coverFiles = coverFiles,
            rewriteFiles = rewriteFiles,
        )
    }

    private fun Map<*, *>.getString(key: String): String = get(key) as String

    /** metricFiles is optional: backups written before the Trends feature shipped omit it, and a
     *  restore then leaves each story with empty history. When present it must be well-formed. */
    private fun validateMetricFiles(
        metricFiles: Any?,
        ids: Set<String>,
    ): String? {
        if (metricFiles == null) return null
        if (metricFiles !is List<*>) return "Invalid full backup: malformed metric file index"
        if (metricFiles.size > ids.size) return "Invalid full backup: too many metric files"
        val metricEntries = metricFiles.map { it as? Map<*, *> ?: return "Invalid full backup: malformed metric file index" }
        if (
            metricEntries.any { entry ->
                val storyId = entry["storyId"] as? String
                val path = entry["path"] as? String
                storyId.isNullOrBlank() ||
                    storyId !in ids ||
                    path.isNullOrBlank() ||
                    !BackupInputLimits.isAllowedFullBackupEntry(path, directory = false)
            }
        ) {
            return "Invalid full backup: malformed metric file index"
        }
        val metricPaths = metricEntries.map { it["path"] as String }
        if (metricPaths.distinct().size != metricPaths.size) return "Invalid full backup: duplicate metric paths"
        val metricStoryIds = metricEntries.map { it["storyId"] as String }
        if (metricStoryIds.distinct().size != metricStoryIds.size) return "Invalid full backup: duplicate metric entries"
        return null
    }

    /** coverFiles is optional, mirroring metricFiles: backups written before AI covers shipped omit
     *  it, and a restore then falls back to each story's source cover URL. */
    private fun validateCoverFiles(
        coverFiles: Any?,
        ids: Set<String>,
    ): String? {
        if (coverFiles == null) return null
        if (coverFiles !is List<*>) return "Invalid full backup: malformed cover file index"
        if (coverFiles.size > ids.size) return "Invalid full backup: too many cover files"
        val coverEntries = coverFiles.map { it as? Map<*, *> ?: return "Invalid full backup: malformed cover file index" }
        if (
            coverEntries.any { entry ->
                val storyId = entry["storyId"] as? String
                val path = entry["path"] as? String
                storyId.isNullOrBlank() ||
                    storyId !in ids ||
                    path.isNullOrBlank() ||
                    // The exporter only ever records covers under their own covers/ path, and the
                    // staged copy relies on that tree — entries pointing elsewhere are rejected even
                    // when the general entry allowlist would accept them (e.g. a novels/ chapter).
                    !path.startsWith("covers/") ||
                    !BackupInputLimits.isAllowedFullBackupEntry(path, directory = false)
            }
        ) {
            return "Invalid full backup: malformed cover file index"
        }
        val coverPaths = coverEntries.map { it["path"] as String }
        if (coverPaths.distinct().size != coverPaths.size) return "Invalid full backup: duplicate cover paths"
        val coverStoryIds = coverEntries.map { it["storyId"] as String }
        if (coverStoryIds.distinct().size != coverStoryIds.size) return "Invalid full backup: duplicate cover entries"
        return null
    }

    /** rewriteFiles is optional, mirroring metric/cover files: backups written before Chapter
     *  polish shipped omit it, and a restore then has no polished variants. Many entries per story
     *  are legitimate — one applied.html per polished chapter plus the per-story manifest. */
    private fun validateRewriteFiles(
        rewriteFiles: Any?,
        ids: Set<String>,
    ): String? {
        if (rewriteFiles == null) return null
        if (rewriteFiles !is List<*>) return "Invalid full backup: malformed rewrite file index"
        if (rewriteFiles.size > BackupInputLimits.MAX_CHAPTER_FILES) return "Invalid full backup: too many rewrite files"
        val rewriteEntries = rewriteFiles.map { it as? Map<*, *> ?: return "Invalid full backup: malformed rewrite file index" }
        if (
            rewriteEntries.any { entry ->
                val storyId = entry["storyId"] as? String
                val path = entry["path"] as? String
                storyId.isNullOrBlank() ||
                    storyId !in ids ||
                    path.isNullOrBlank() ||
                    // The exporter only ever records rewrites under their own chapter_rewrites/ tree;
                    // entries pointing elsewhere are rejected even when the general entry allowlist
                    // would accept them (e.g. a novels/ chapter).
                    !path.startsWith("chapter_rewrites/") ||
                    !BackupInputLimits.isAllowedFullBackupEntry(path, directory = false)
            }
        ) {
            return "Invalid full backup: malformed rewrite file index"
        }
        val rewritePaths = rewriteEntries.map { it["path"] as String }
        if (rewritePaths.distinct().size != rewritePaths.size) return "Invalid full backup: duplicate rewrite paths"
        return null
    }
}
