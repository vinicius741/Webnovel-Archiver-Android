package com.vinicius741.webnovelarchiver.data.backup

import java.util.Locale

object BackupExportPlanning {
    private const val MAX_JSON_BACKUP_BYTES = 50L * 1024L * 1024L

    fun validateJsonBackup(
        librarySize: Int,
        jsonByteCount: Long,
    ): String? {
        if (librarySize == 0) return "Your library is empty"
        if (jsonByteCount > MAX_JSON_BACKUP_BYTES) {
            val sizeMb = jsonByteCount.toDouble() / (1024.0 * 1024.0)
            return "Backup is too large (${String.format(Locale.US, "%.1f", sizeMb)} MB). Consider exporting fewer novels."
        }
        return null
    }

    fun validateFullBackup(librarySize: Int): String? {
        if (librarySize == 0) return "Your library is empty"
        return null
    }
}
