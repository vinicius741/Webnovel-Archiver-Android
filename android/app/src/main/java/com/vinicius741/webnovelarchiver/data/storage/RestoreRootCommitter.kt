package com.vinicius741.webnovelarchiver.data.storage

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
            storage.covers.ensureAppliedDirectory()
            storage.backupRoot.mkdirs()
        },
    )

    private var rollbackRequired = false
    private var preparedByThisAttempt = false

    fun stageBesideLiveRoot(staged: File): File {
        // When the staged tree already shares a parent directory with the live root, the swap can
        // rename it directly. Otherwise move it beside the live root first (cache vs filesDir), so
        // the swap's rename never has to fall back to a copy after the old root has moved. The
        // move is a rename on Android; a verified copy only when the rename is refused.
        if (sharesParentDirectory(staged, liveRoot)) return staged
        val parent = checkNotNull(liveRoot.parentFile) { "Live storage root has no parent" }
        val candidate = File(parent, "${RestoreStartupRecovery.SWAP_CANDIDATE_PREFIX}${System.currentTimeMillis()}")
        RestoreTreeMove(rootSwap.files).move(staged, candidate)
        return candidate
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
                    LocalImageRevision.rootReplaced()
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
                onPhase = journal::write,
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

    private fun sharesParentDirectory(
        first: File,
        second: File,
    ): Boolean {
        val firstParent = runCatching { first.canonicalFile.parentFile?.absolutePath }.getOrNull() ?: return false
        val secondParent = runCatching { second.canonicalFile.parentFile?.absolutePath }.getOrNull() ?: return false
        return firstParent == secondParent
    }
}
