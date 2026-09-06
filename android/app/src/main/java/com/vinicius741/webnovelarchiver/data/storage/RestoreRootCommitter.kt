package com.vinicius741.webnovelarchiver.data.storage

import com.vinicius741.webnovelarchiver.data.backup.BackupInputLimits
import timber.log.Timber
import java.io.File

internal class RestoreRootCommitter(
    private val liveRoot: File,
    private val snapshot: File,
    private val journal: RestoreTransactionJournal,
    private val initializeRoot: () -> Unit,
    private val rootSwap: RestoreRootSwap = RestoreRootSwap(),
) {
    constructor(storage: AppStorage) : this(
        liveRoot = storage.root,
        snapshot = storage.preRestoreSnapshotDir,
        journal = RestoreTransactionJournal(File(storage.context.filesDir, RestoreTransactionJournal.FILE_NAME)),
        initializeRoot = {
            storage.root.mkdirs()
            storage.storyDir.mkdirs()
            storage.metricDir.mkdirs()
            storage.chapterRoot.mkdirs()
            storage.epubRoot.mkdirs()
            storage.coverFiles.ensureDirectory()
            storage.backupRoot.mkdirs()
        },
    )

    private var rollbackRequired = false
    private var preparedByThisAttempt = false

    fun stageBesideLiveRoot(staged: File): File {
        // When the staged tree already shares a parent directory with the live root, rename can
        // replace it directly. Otherwise copy beside the live root first (cache vs filesDir).
        if (sharesParentDirectory(staged, liveRoot)) return staged
        val parent = checkNotNull(liveRoot.parentFile) { "Live storage root has no parent" }
        val candidate = File(parent, "webnovel_restore_swap_${System.currentTimeMillis()}")
        val stagedBytes = directoryByteCount(staged)
        check(BackupInputLimits.hasSwapSpace(parent.usableSpace, stagedBytes)) {
            "Not enough free app-storage space to commit this restore"
        }
        return copyVerified(staged, candidate, stagedBytes)
    }

    fun commit(source: File) {
        // R07: the durable phase journal makes a process death between root moves recoverable at
        // the next startup; it is cleared only once the swap (including snapshot cleanup) returned.
        check(!snapshot.exists() && !journal.exists()) {
            "A previous restore still requires recovery"
        }
        journal.write(RestoreTransactionJournal.Phase.PREPARED)
        preparedByThisAttempt = true
        rootSwap.swap(
            source = source,
            liveRoot = liveRoot,
            snapshot = snapshot,
            initializeRoot = initializeRoot,
            onPhase = { phase ->
                if (phase == RestoreTransactionJournal.Phase.OLD_ROOT_MOVED) rollbackRequired = true
                journal.write(phase)
                if (phase == RestoreTransactionJournal.Phase.COMMITTED) {
                    rollbackRequired = false
                    preparedByThisAttempt = false
                }
            },
        )
        if (!snapshot.exists()) journal.clear()
    }

    /**
     * Restores the pre-swap snapshot when present.
     *
     * @return `true` when either no snapshot needed restoration (library was never moved) or the
     * snapshot was successfully restored; `false` when a snapshot existed but rollback failed.
     */
    fun rollback(): Boolean {
        // Validation failures and leftover snapshots from an earlier attempt do not belong to
        // this transaction. Never roll those over the current library.
        if (!rollbackRequired) {
            if (preparedByThisAttempt && !snapshot.exists()) {
                journal.clear()
                preparedByThisAttempt = false
            }
            return true
        }
        if (!snapshot.exists()) {
            journal.clear()
            return true
        }
        val restored =
            rootSwap.rollback(
                liveRoot = liveRoot,
                snapshot = snapshot,
                initializeRoot = initializeRoot,
            )
        if (restored) {
            rollbackRequired = false
            preparedByThisAttempt = false
            // The rollback undid the swap and consumed the snapshot; a leftover OLD_ROOT_MOVED
            // journal would make the next startup treat the intact root as a half-installed one.
            journal.clear()
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
