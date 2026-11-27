package com.vinicius741.webnovelarchiver.data.storage

import com.vinicius741.webnovelarchiver.data.backup.BackupInputLimits
import timber.log.Timber
import java.io.File

/**
 * Moves restore trees without rewriting their data. Full restore writes the backup's files once,
 * during extraction; every later step (raw extraction → staged root → swap candidate beside the
 * live root) is a rename. cacheDir and filesDir share a filesystem on Android, so the renames
 * succeed. When one is refused anyway (another filesystem, an odd OEM layout), the move falls back
 * to a copy that is space-checked first, verified byte-for-byte by size, and removed if incomplete.
 *
 * Every move here happens before the restore journal is written, so the live root is untouched
 * and a process death only leaves staging debris that [RestoreStartupRecovery] sweeps.
 */
internal class RestoreTreeMove(
    private val files: RestoreFileOperations = DefaultRestoreFileOperations,
    private val usableSpace: (File) -> Long = File::getUsableSpace,
) {
    /** Moves the allowlisted data trees of a raw extraction into the staged root. */
    fun adoptExtractedTrees(
        raw: File,
        staged: File,
    ) {
        // novels/ holds chapter text, metrics/ the trend history, covers/ generated AI covers. Older
        // backups may also carry chapter_rewrites/ from the retired Chapter polish feature: it was
        // validated during extraction but is deliberately not staged.
        ADOPTED_TREES.forEach { name ->
            val source = File(raw, name)
            if (source.exists()) move(source, File(staged, name))
        }
    }

    fun move(
        source: File,
        destination: File,
    ) {
        check(source.exists()) { "Restore tree ${source.name} is missing" }
        check(!destination.exists()) { "Restore target ${destination.name} already exists" }
        destination.parentFile?.let { parent -> check(parent.mkdirs() || parent.isDirectory) { "Could not create ${parent.name}" } }
        if (files.rename(source, destination)) {
            check(destination.exists() && !source.exists()) { "Restore move of ${source.name} did not complete" }
            return
        }
        check(!destination.exists()) { "Restore rename of ${source.name} failed after creating a partial target" }
        copyVerified(source, destination)
        // The verified copy is complete. A leftover source is only space until the caller's
        // cleanup of the restore work directory, never data the restore reads again.
        if (!files.deleteTree(source)) Timber.w("Could not remove a copied restore tree")
    }

    private fun copyVerified(
        source: File,
        destination: File,
    ) {
        val expectedBytes = byteCount(source)
        val parent = checkNotNull(destination.parentFile) { "Restore target has no parent" }
        check(BackupInputLimits.hasSwapSpace(usableSpace(parent), expectedBytes)) {
            "Not enough free app-storage space to commit this restore"
        }
        var completed = false
        try {
            files.copyTree(source, destination)
            check(byteCount(destination) == expectedBytes) { "Restore copy of ${source.name} did not complete" }
            completed = true
        } finally {
            if (!completed && destination.exists() && !files.deleteTree(destination)) {
                Timber.w("Could not remove an incomplete restore copy")
            }
        }
    }

    private fun byteCount(file: File): Long = file.walkTopDown().filter(File::isFile).sumOf(File::length)

    private companion object {
        val ADOPTED_TREES = listOf("novels", "metrics", "covers")
    }
}
