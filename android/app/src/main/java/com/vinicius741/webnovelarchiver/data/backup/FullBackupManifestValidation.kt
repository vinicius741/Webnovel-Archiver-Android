package com.vinicius741.webnovelarchiver.data.backup

object FullBackupManifestValidation {
    private const val FORMAT = "webnovel-archiver-full-backup"
    const val MISSING_MANIFEST_MESSAGE = "Invalid full backup: missing manifest"

    fun validate(manifest: Map<String, Any>?): String? {
        if (manifest == null || manifest["format"] != FORMAT) {
            return "Invalid full backup: unsupported format"
        }
        if (manifest["version"] == null) {
            return "Invalid full backup: missing version"
        }
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
        if (!library.all { story -> story is Map<*, *> && story["id"] is String }) {
            return "Invalid full backup: malformed story data"
        }
        return null
    }
}
