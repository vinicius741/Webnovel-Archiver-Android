package com.vinicius741.webnovelarchiver.data.backup

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
        return null
    }
}
