package com.vinicius741.webnovelarchiver.data.backup

object JsonBackupValidation {
    fun validate(payload: Map<String, Any?>): String? {
        if (!payload.containsKey("version")) return "Invalid backup file: missing version"
        val library = payload["library"]
        if (library !is List<*>) return "Invalid backup file: missing library"
        val allStoriesHaveIds =
            library.all { item ->
                val story = item as? Map<*, *> ?: return@all false
                story["id"] is String
            }
        if (!allStoriesHaveIds) return "Invalid backup file: malformed story data"
        return null
    }
}
