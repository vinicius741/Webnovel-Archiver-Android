package com.vinicius741.webnovelarchiver.data.storage

import com.vinicius741.webnovelarchiver.data.backup.BackupInputLimits
import timber.log.Timber
import java.io.File

internal class RestoreRootCommitter(
    private val storage: AppStorage,
    private val rootSwap: RestoreRootSwap = RestoreRootSwap(),
) {
    fun stageBesideLiveRoot(staged: File): File {
        // When the staged tree already shares a parent directory with the live root, rename can
        // replace it directly. Otherwise copy beside the live root first (cache vs filesDir).
        if (sharesParentDirectory(staged, storage.root)) return staged
        val parent = checkNotNull(storage.root.parentFile) { "Live storage root has no parent" }
        val candidate = File(parent, "webnovel_restore_swap_${System.currentTimeMillis()}")
        val stagedBytes = directoryByteCount(staged)
        check(BackupInputLimits.hasSwapSpace(parent.usableSpace, stagedBytes)) {
            "Not enough free app-storage space to commit this restore"
        }
        return copyVerified(staged, candidate, stagedBytes)
    }

    fun commit(source: File) {
        rootSwap.swap(
            source = source,
            liveRoot = storage.root,
            snapshot = storage.preRestoreSnapshotDir,
            initializeRoot = ::initializeStorageDirectories,
        )
    }

    /**
     * Restores the pre-swap snapshot when present.
     *
     * @return `true` when either no snapshot needed restoration (library was never moved) or the
     * snapshot was successfully restored; `false` when a snapshot existed but rollback failed.
     */
    fun rollback(): Boolean {
        if (!storage.preRestoreSnapshotDir.exists()) return true
        val restored =
            rootSwap.rollback(
                liveRoot = storage.root,
                snapshot = storage.preRestoreSnapshotDir,
                initializeRoot = ::initializeStorageDirectories,
            )
        if (restored) {
            Timber.w("Restored previous library from pre-restore snapshot after a failed swap.")
        } else {
            Timber.e("Failed to roll back root from pre-restore snapshot; snapshot was preserved for recovery.")
        }
        return restored
    }

    private fun copyVerified(
        source: File,
        candidate: File,
        expectedBytes: Long,
    ): File {
        var completed = false
        try {
            check(source.copyRecursively(candidate, overwrite = true)) {
                "Could not copy the verified restore tree beside live storage"
            }
            check(directoryByteCount(candidate) == expectedBytes) { "Restore swap candidate did not copy completely" }
            completed = true
            return candidate
        } finally {
            if (!completed) candidate.deleteRecursively()
        }
    }

    private fun initializeStorageDirectories() {
        storage.root.mkdirs()
        storage.storyDir.mkdirs()
        storage.metricDir.mkdirs()
        storage.chapterRoot.mkdirs()
        storage.epubRoot.mkdirs()
        storage.backupRoot.mkdirs()
    }

    private fun sharesParentDirectory(
        first: File,
        second: File,
    ): Boolean {
        val firstParent = runCatching { first.canonicalFile.parentFile?.absolutePath }.getOrNull() ?: return false
        val secondParent = runCatching { second.canonicalFile.parentFile?.absolutePath }.getOrNull() ?: return false
        return firstParent == secondParent
    }

    private fun directoryByteCount(directory: File): Long = directory.walkTopDown().filter(File::isFile).sumOf(File::length)
}
