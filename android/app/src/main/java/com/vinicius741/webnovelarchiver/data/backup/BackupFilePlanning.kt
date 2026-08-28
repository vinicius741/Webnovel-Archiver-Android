package com.vinicius741.webnovelarchiver.data.backup

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pure naming/formatting rules for files in the backups directory: orphaned atomic-write temp
 * detection, artifact identification and labels, and human-readable size/date rendering.
 */
object BackupFilePlanning {
    /**
     * [com.vinicius741.webnovelarchiver.data.storage.AtomicFileWrites] temps are
     * `<destination>.tmp.<n>`. A temp surviving into a fresh process is garbage: a successful
     * write renames the temp onto its destination, so only a process death mid-write leaves one.
     */
    private val tempSuffix = Regex("\\.tmp\\.\\d+$")

    private val timestampFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm a", Locale.US)

    fun isOrphanTempFile(name: String): Boolean = tempSuffix.containsMatchIn(name)

    /** True for the library metadata, full, and cleanup-rules exports the app itself creates. */
    fun isBackupArtifact(name: String): Boolean =
        name.startsWith("webnovel_backup_") ||
            name.startsWith("webnovel_full_backup_") ||
            name.startsWith("webnovel_cleanup_rules_")

    fun artifactLabel(name: String): String =
        when {
            name.startsWith("webnovel_full_backup_") -> "Full backup"
            name.startsWith("webnovel_backup_") -> "Metadata backup"
            name.startsWith("webnovel_cleanup_rules_") -> "Cleanup rules backup"
            else -> name.substringBeforeLast('.')
        }

    fun sizeLabel(bytes: Long): String =
        when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
            else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        }

    fun timestampLabel(millis: Long): String = timestampFormatter.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

    /** Backup artifacts in [directory], newest first. Temp files and shared diagnostics are excluded. */
    fun listArtifacts(directory: File): List<File> =
        directory
            .listFiles()
            .orEmpty()
            .filter { it.isFile && !isOrphanTempFile(it.name) && isBackupArtifact(it.name) }
            .sortedByDescending { it.lastModified() }

    /**
     * Deletes orphaned atomic-write temps under [directory], returning the number removed. Only
     * safe to call when no export can be writing — i.e. at storage construction (process start).
     */
    fun sweepOrphanTempFiles(directory: File): Int =
        directory
            .listFiles()
            .orEmpty()
            .filter { it.isFile && isOrphanTempFile(it.name) }
            .count { it.delete() }

    /** Deletes [file] only when it lives directly inside [directory]; refuses anything else. */
    fun deleteWithin(
        directory: File,
        file: File,
    ): Boolean = file.parentFile == directory && file.isFile && file.delete()
}
