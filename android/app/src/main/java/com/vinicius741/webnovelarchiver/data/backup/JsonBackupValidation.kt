package com.vinicius741.webnovelarchiver.data.backup

object JsonBackupValidation {
    fun validate(payload: Map<String, Any?>): String? {
        val version =
            BackupInputLimits.exactInt(payload["version"])
                ?: return "Invalid backup file: missing version"
        if (version !in setOf(1, 2)) return "Invalid backup file: unsupported version $version"
        val library = payload["library"]
        if (library !is List<*>) return "Invalid backup file: missing library"
        if (library.size > BackupInputLimits.MAX_STORIES) return "Invalid backup file: too many stories"
        val allStoriesHaveIds =
            library.all { item ->
                val story = item as? Map<*, *> ?: return@all false
                story["id"] is String
            }
        if (!allStoriesHaveIds) return "Invalid backup file: malformed story data"
        val ids = library.map { (it as Map<*, *>)["id"] as String }
        if (ids.any(String::isBlank)) return "Invalid backup file: malformed story data"
        if (ids.distinct().size != ids.size) return "Invalid backup file: duplicate story IDs"
        return null
    }
}
