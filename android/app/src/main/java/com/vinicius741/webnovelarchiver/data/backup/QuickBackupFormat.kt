package com.vinicius741.webnovelarchiver.data.backup

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.story.StoryNormalization

/**
 * The quick backup: a small ZIP with the library's metadata and settings but no chapter text
 * (`docs/architecture/quick-backup.md`). It is its own format, not a full-backup version: the
 * first ZIP entry is [MANIFEST_ENTRY] (a full backup starts with `manifest.json`), the manifest's
 * `format` is [FORMAT], and the file name starts with [FILE_PREFIX]. An app without quick backups
 * rejects the file as a full backup without a manifest and changes nothing.
 */
object QuickBackupFormat {
    const val FORMAT = "webnovel-archiver-quick-backup"
    const val VERSION = 1
    const val MANIFEST_ENTRY = "quick_backup.json"
    const val FILE_PREFIX = "webnovel_quick_backup_"

    /** Whether [name] is an entry a quick backup may hold: the manifest, `metrics/<id>.json` or `covers/<name>.<image>`. */
    fun isAllowedEntry(name: String): Boolean =
        name == MANIFEST_ENTRY ||
            (
                (name.startsWith("metrics/") || name.startsWith("covers/")) &&
                    BackupInputLimits.isAllowedFullBackupEntry(name, directory = false)
            )

    /** Manifest data after the untrusted JSON passed [validate]. */
    data class Manifest(
        val exportDate: String?,
        val library: List<Story>,
        val config: Map<String, Any>,
        val metricFiles: List<RestoredMetricFileIndex>,
        val coverFiles: List<RestoredCoverFileIndex>,
    )

    fun validate(manifest: Map<String, Any?>?): String? {
        if (manifest == null || manifest["format"] != FORMAT) return "Invalid quick backup: unsupported format"
        val version = BackupInputLimits.exactInt(manifest["version"]) ?: return "Invalid quick backup: missing version"
        if (version != VERSION) return "Invalid quick backup: unsupported version $version"
        val library = manifest["library"] as? List<*> ?: return "Invalid quick backup: missing library"
        if (library.size > BackupInputLimits.MAX_STORIES) return "Invalid quick backup: too many stories"
        val ids =
            library.map { story ->
                (story as? Map<*, *>)?.get("id") as? String ?: return "Invalid quick backup: malformed story data"
            }
        if (ids.any(String::isBlank)) return "Invalid quick backup: malformed story data"
        if (ids.distinct().size != ids.size) return "Invalid quick backup: duplicate story IDs"
        val config = manifest["config"]
        if (config !is Map<*, *>) return "Invalid quick backup: missing configuration"
        val idSet = ids.toSet()
        validateIndex(manifest["metricFiles"], idSet, "metrics/", "metric")?.let { return it }
        return validateIndex(manifest["coverFiles"], idSet, "covers/", "cover")
    }

    /** Converts a manifest that passed [validate]; stories get the same default coercion as a library read. */
    fun parseValidated(
        gson: Gson,
        manifest: Map<String, Any?>,
    ): Manifest {
        validate(manifest)?.let(::error)
        val stories: List<Story> =
            gson.fromJson<List<Story>>(gson.toJson(manifest["library"]), object : TypeToken<List<Story>>() {}.type)
                ?: error("Invalid quick backup: missing library")
        @Suppress("SENSELESS_COMPARISON") // Gson can null a Kotlin non-null list at runtime.
        stories.forEach { story -> if (story.chapters == null) story.chapters = mutableListOf() }
        val config: Map<String, Any> =
            gson.fromJson(gson.toJson(manifest["config"]), object : TypeToken<Map<String, Any>>() {}.type)
                ?: error("Invalid quick backup: missing configuration")
        return Manifest(
            exportDate = manifest["exportDate"] as? String,
            library = stories.map { StoryNormalization.coerceDefaults(it).story },
            config = config,
            metricFiles = index(manifest["metricFiles"]).map { (storyId, path) -> RestoredMetricFileIndex(storyId, path) },
            coverFiles = index(manifest["coverFiles"]).map { (storyId, path) -> RestoredCoverFileIndex(storyId, path) },
        )
    }

    private fun index(raw: Any?): List<Pair<String, String>> =
        (raw as? List<*>).orEmpty().map { entry ->
            val map = entry as Map<*, *>
            map["storyId"] as String to map["path"] as String
        }

    /** An optional `{storyId, path}` list: one entry per story at most, each under [prefix]. */
    private fun validateIndex(
        raw: Any?,
        ids: Set<String>,
        prefix: String,
        label: String,
    ): String? {
        if (raw == null) return null
        val entries = raw as? List<*> ?: return "Invalid quick backup: malformed $label file index"
        if (entries.size > ids.size) return "Invalid quick backup: too many $label files"
        val pairs =
            entries.map { entry ->
                val map = entry as? Map<*, *> ?: return "Invalid quick backup: malformed $label file index"
                val storyId = map["storyId"] as? String
                val path = map["path"] as? String
                if (storyId.isNullOrBlank() || storyId !in ids || path.isNullOrBlank() || !path.startsWith(prefix) ||
                    !isAllowedEntry(path)
                ) {
                    return "Invalid quick backup: malformed $label file index"
                }
                storyId to path
            }
        if (pairs.map { it.first }.distinct().size != pairs.size) return "Invalid quick backup: duplicate $label entries"
        if (pairs.map { it.second }.distinct().size != pairs.size) return "Invalid quick backup: duplicate $label paths"
        return null
    }
}
