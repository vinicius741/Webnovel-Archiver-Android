package com.vinicius741.webnovelarchiver.data.storage

import android.util.Log
import com.vinicius741.webnovelarchiver.data.diagnostics.LocalDiagnostics
import timber.log.Timber
import java.io.File

/**
 * Durable restore-transaction record kept beside the live root (not in cache), with explicit
 * phases. A process death between root moves bypasses every in-process `catch`/`finally`;
 * this journal is what lets the next startup recover instead of silently proceeding on a missing
 * or half-installed root.
 *
 * JSON backup imports are additive merges (existing stories are never replaced) and keep their
 * exception-time rollback snapshot, so a killed import degrades to a partial add that re-import
 * completes; the journal discipline here covers the destructive full-restore swap.
 */
internal class RestoreTransactionJournal(
    private val journalFile: File,
) {
    enum class Phase {
        /** Staging verified; nothing moved yet. The live root is still the old, intact data. */
        PREPARED,

        /** The old live root was renamed onto the snapshot; the new root is not installed yet. */
        OLD_ROOT_MOVED,

        /** The new root is installed and initialized. Any leftover snapshot is deletable, never restorable. */
        COMMITTED,

        /**
         * The snapshot is back in the live root (rollback or startup recovery). The live root is the
         * pre-restore library; any leftover snapshot is a partial delete, never restorable. Without
         * this phase a death while deleting a copied-back snapshot would look like `OLD_ROOT_MOVED`
         * and the next startup would replace the complete library with the half-deleted snapshot.
         */
        ROLLED_BACK,
    }

    fun write(phase: Phase) {
        journalFile.parentFile?.mkdirs()
        AtomicFileWrites.writeText(journalFile, phase.name)
    }

    fun read(): Phase? =
        runCatching { journalFile.takeIf(File::isFile)?.readText()?.trim() }
            .getOrNull()
            ?.let { text -> Phase.entries.firstOrNull { it.name == text } }

    fun exists(): Boolean = journalFile.exists()

    fun clear() {
        journalFile.delete()
    }

    companion object {
        /** Lives directly in filesDir beside the live root: durable, never cache-purgeable. */
        const val FILE_NAME = "webnovel_archiver_restore_journal.txt"
    }
}

/**
 * Runs once at process start, BEFORE [AppStorage] creates or hydrates the live root.
 *
 * Decision table. "live"/"snapshot" are the durable directories beside each other in filesDir.
 * Every row resolves to exactly one outcome; rows marked *fail closed* keep the journal and every
 * directory so nothing is deleted and the next restore refuses to run.
 *
 * | Journal         | Live    | Snapshot | Outcome                                                        |
 * |-----------------|---------|----------|----------------------------------------------------------------|
 * | unreadable      | any     | any      | fail closed                                                    |
 * | COMMITTED       | present | any      | keep live, delete snapshot debris, clear journal               |
 * | COMMITTED       | missing | any      | fail closed                                                    |
 * | ROLLED_BACK     | present | any      | keep live, delete snapshot debris, clear journal               |
 * | ROLLED_BACK     | missing | any      | fail closed                                                    |
 * | OLD_ROOT_MOVED  | any     | present  | move snapshot back over live (write ROLLED_BACK), then as above |
 * | OLD_ROOT_MOVED  | present | missing  | a rollback move finished; keep live, clear journal             |
 * | OLD_ROOT_MOVED  | missing | missing  | nothing left to recover; clear journal so restore can run      |
 * | PREPARED        | present | missing  | died before the swap; clear journal                            |
 * | PREPARED        | present | empty    | died before the swap; delete empty snapshot, clear journal     |
 * | PREPARED        | present | non-empty| ambiguous; fail closed                                         |
 * | PREPARED        | missing | present  | the old root moved before the phase write; as OLD_ROOT_MOVED   |
 * | PREPARED        | missing | missing  | nothing left to recover; clear journal so restore can run      |
 * | none            | any     | present  | durable snapshot without a journal; fail closed                |
 * | none            | any     | missing  | legacy cache-snapshot rules ([recoverLegacyState])             |
 *
 * Each step that moves data writes the next phase first, so re-running recovery after a death
 * inside recovery lands on a later row of the same table. `RestoreCrashRecoveryTest` kills the
 * swap, rollback, and recovery at every filesystem operation and checks this property.
 *
 * Once no journal remains, staging copies left beside the live root by a killed restore are swept;
 * they are never read by recovery and can be as large as the whole library.
 */
internal object RestoreStartupRecovery {
    fun recover(context: android.content.Context) {
        val appContext = context.applicationContext
        val liveRoot = File(appContext.filesDir, "webnovel_archiver")
        val durableSnapshot = File(appContext.filesDir, SNAPSHOT_DIR_NAME)
        val legacyCacheSnapshot = File(appContext.cacheDir, SNAPSHOT_DIR_NAME)
        val journal = RestoreTransactionJournal(File(appContext.filesDir, RestoreTransactionJournal.FILE_NAME))
        recoverSafely(liveRoot, durableSnapshot, legacyCacheSnapshot, journal)
        if (!journal.exists()) {
            sweepOrphanedStaging(appContext.filesDir, File(appContext.cacheDir, RESTORE_WORK_DIR_NAME))
        }
    }

    /**
     * Fail-closed wrapper: a recovery error must never crash startup, but it does leave a durable
     * metadata-only trace — the exception class in the sticky WARN ring, and a sanitized phase
     * token (never paths) that attributes the failure to an interrupted restore.
     */
    internal fun recoverSafely(
        liveRoot: File,
        durableSnapshot: File,
        legacyCacheSnapshot: File,
        journal: RestoreTransactionJournal,
    ) {
        val startedAt = System.nanoTime() / 1_000_000L
        runCatching { recoverState(liveRoot, durableSnapshot, legacyCacheSnapshot, journal) }
            .onFailure { failure ->
                Timber.e(failure, "Restore startup recovery failed; failing closed")
                LocalDiagnostics.record(Log.ERROR, failure)
                LocalDiagnostics.recordOperation(
                    operation = "restore_recovery_failed_" + (journal.read()?.name?.lowercase() ?: "legacy"),
                    durationMillis = System.nanoTime() / 1_000_000L - startedAt,
                    failed = true,
                )
            }
    }

    /**
     * Process start is the only moment no restore can be running, so every staging copy beside
     * the live root and every extraction directory is debris from a killed attempt.
     */
    internal fun sweepOrphanedStaging(
        filesDir: File,
        restoreWorkDir: File,
    ) {
        filesDir
            .listFiles { file -> file.name.startsWith(SWAP_CANDIDATE_PREFIX) }
            .orEmpty()
            .forEach { candidate ->
                if (!candidate.deleteRecursively()) Timber.w("Could not remove an orphaned restore staging copy")
            }
        restoreWorkDir.listFiles().orEmpty().forEach { it.deleteRecursively() }
    }

    internal fun recoverState(
        liveRoot: File,
        durableSnapshot: File,
        legacyCacheSnapshot: File,
        journal: RestoreTransactionJournal,
        files: RestoreFileOperations = DefaultRestoreFileOperations,
    ) {
        val phase = journal.read()
        check(phase != null || !journal.exists()) { "Restore journal is unreadable; preserving recovery data" }
        when (phase) {
            RestoreTransactionJournal.Phase.COMMITTED -> {
                // New data is authoritative; a leftover snapshot is cleanup debris, not a rollback.
                check(liveRoot.isDirectory) { "Committed restore root is missing; preserving recovery data" }
                check(files.deleteTree(durableSnapshot)) { "Could not clean the committed restore snapshot" }
                check(files.deleteTree(legacyCacheSnapshot)) { "Could not clean the legacy restore snapshot" }
                journal.clear()
                Timber.i("Recovered an interrupted restore: committed root kept, leftover snapshot removed")
            }
            RestoreTransactionJournal.Phase.ROLLED_BACK -> {
                // The pre-restore library is already back in place; the snapshot is a partial delete.
                check(liveRoot.isDirectory) { "Rolled-back restore root is missing; preserving recovery data" }
                check(files.deleteTree(durableSnapshot)) { "Could not clean the rolled-back restore snapshot" }
                journal.clear()
                Timber.i("Recovered an interrupted restore: rollback finished, leftover snapshot removed")
            }
            RestoreTransactionJournal.Phase.OLD_ROOT_MOVED -> recoverMovedRoot(liveRoot, durableSnapshot, journal, files)
            RestoreTransactionJournal.Phase.PREPARED -> {
                if (!liveRoot.exists()) {
                    // The old root may have moved before the phase write. Persist the recovery
                    // phase before copying, so another crash cannot make a partial copy look like
                    // the intact pre-swap root.
                    if (durableSnapshot.isDirectory) journal.write(RestoreTransactionJournal.Phase.OLD_ROOT_MOVED)
                    recoverMovedRoot(liveRoot, durableSnapshot, journal, files)
                } else {
                    if (durableSnapshot.exists()) {
                        check(durableSnapshot.listFiles()?.isEmpty() == true) { "Prepared restore has ambiguous roots" }
                        check(files.deleteTree(durableSnapshot)) { "Could not remove the empty restore snapshot" }
                    }
                    journal.clear()
                    Timber.i("Recovered an interrupted restore: it died before the swap; live root untouched")
                }
            }
            null -> recoverLegacyState(liveRoot, durableSnapshot, legacyCacheSnapshot)
        }
    }

    private fun recoverMovedRoot(
        liveRoot: File,
        durableSnapshot: File,
        journal: RestoreTransactionJournal,
        files: RestoreFileOperations,
    ) {
        if (!durableSnapshot.exists()) {
            if (liveRoot.isDirectory) {
                // A snapshot only leaves this phase by moving into the live root, so the root is
                // the pre-restore library and only the journal clear was lost. Keeping the journal
                // would refuse every future restore without protecting any data.
                Timber.w("Recovered an interrupted restore: rollback move had finished; live root kept")
            } else {
                // Nothing exists to preserve. Keeping the journal would only block restoring a backup.
                LocalDiagnostics.recordOperation(operation = "restore_recovery_no_data", durationMillis = 0L, failed = true)
                Timber.e("Interrupted restore left neither a live root nor a snapshot")
            }
            journal.clear()
            return
        }
        if (liveRoot.exists()) {
            check(files.deleteTree(liveRoot) && !liveRoot.exists()) { "Could not remove the half-installed restore root" }
        }
        val moved =
            files.rename(durableSnapshot, liveRoot) ||
                runCatching {
                    check(!liveRoot.exists()) { "Snapshot rename created a partial live root" }
                    files.copyTree(durableSnapshot, liveRoot)
                }.isSuccess
        check(moved && liveRoot.isDirectory) { "Could not restore the pre-restore snapshot; failing closed" }
        // From here the live root is authoritative; a death while deleting the snapshot must not
        // let the next startup restore the half-deleted copy over it.
        journal.write(RestoreTransactionJournal.Phase.ROLLED_BACK)
        check(files.deleteTree(durableSnapshot)) { "Could not clean the restored snapshot" }
        journal.clear()
        Timber.w("Recovered an interrupted restore: pre-restore snapshot moved back into place")
    }

    /** Pre-journal builds left the snapshot under cacheDir with no phase record. */
    private fun recoverLegacyState(
        liveRoot: File,
        durableSnapshot: File,
        legacyCacheSnapshot: File,
    ) {
        check(!durableSnapshot.exists()) { "Durable restore snapshot has no journal; preserving it for recovery" }
        if (!legacyCacheSnapshot.exists()) return
        val contents = legacyCacheSnapshot.listFiles().orEmpty()
        if (contents.isEmpty()) {
            legacyCacheSnapshot.deleteRecursively()
            return
        }
        if (liveRoot.exists()) {
            // Old flow order: install new root, then clean the snapshot — so a leftover snapshot
            // beside a live root means the commit had completed.
            legacyCacheSnapshot.deleteRecursively()
            Timber.i("Recovered a legacy restore state: committed root kept, leftover cache snapshot removed")
        } else {
            // The swap died between moving the old root and installing the new one.
            check(legacyCacheSnapshot.renameTo(liveRoot) || copyBack(legacyCacheSnapshot, liveRoot)) {
                "Could not restore the legacy pre-restore snapshot; failing closed"
            }
            legacyCacheSnapshot.deleteRecursively()
            Timber.w("Recovered a legacy restore state: snapshot moved back into place")
        }
    }

    private fun copyBack(
        snapshot: File,
        liveRoot: File,
    ): Boolean =
        runCatching {
            check(snapshot.copyRecursively(liveRoot, overwrite = false)) { "snapshot copy failed" }
        }.isSuccess

    private const val SNAPSHOT_DIR_NAME = "webnovel_restore_snapshot"

    /** Must match the staging copy name chosen by [RestoreRootCommitter.stageBesideLiveRoot]. */
    internal const val SWAP_CANDIDATE_PREFIX = "webnovel_restore_swap_"

    /** Must match [AppStorage.restoreRoot]. */
    private const val RESTORE_WORK_DIR_NAME = "webnovel_restore"
}
