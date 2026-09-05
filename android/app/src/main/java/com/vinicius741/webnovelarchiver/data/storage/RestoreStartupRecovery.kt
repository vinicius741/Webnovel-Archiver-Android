package com.vinicius741.webnovelarchiver.data.storage

import timber.log.Timber
import java.io.File

/**
 * Durable restore-transaction record kept beside the live root (not in cache), with explicit
 * phases (R07). A process death between root moves bypasses every in-process `catch`/`finally`;
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
    }

    fun write(phase: Phase) {
        journalFile.parentFile?.mkdirs()
        AtomicFileWrites.writeText(journalFile, phase.name)
    }

    fun read(): Phase? =
        runCatching { journalFile.takeIf(File::isFile)?.readText()?.trim() }
            .getOrNull()
            ?.let { text -> Phase.entries.firstOrNull { it.name == text } }

    fun clear() {
        journalFile.delete()
    }

    companion object {
        /** Lives directly in filesDir beside the live root: durable, never cache-purgeable. */
        const val FILE_NAME = "webnovel_archiver_restore_journal.txt"
    }
}

/**
 * Runs once at process start, BEFORE [AppStorage] creates or hydrates the live root (R07).
 *
 * - `COMMITTED`: the new library is good; a leftover partially deleted snapshot is removed, never
 *   restored over it.
 * - `OLD_ROOT_MOVED`: the process died mid-swap (the live root is missing or a half-copied
 *   install); the snapshot — the only rollback copy, kept in durable app files — is moved back.
 * - `PREPARED`: inspect the roots, since the old root may have moved before the phase write.
 *   Recover a missing live root from the snapshot; otherwise clear only an empty snapshot.
 * - Legacy journal-less states (snapshot from a pre-journal build): the live root's presence is
 *   the discriminator — present means the commit had completed (keep it), missing means the swap
 *   died mid-move (restore the snapshot).
 *
 * Ambiguous or unreadable states fail closed: nothing is deleted except a journal-less committed
 * case, and the next restore's swap refuses to run over a non-empty snapshot.
 */
internal object RestoreStartupRecovery {
    fun recover(context: android.content.Context) {
        val appContext = context.applicationContext
        val liveRoot = File(appContext.filesDir, "webnovel_archiver")
        val durableSnapshot = File(appContext.filesDir, SNAPSHOT_DIR_NAME)
        val legacyCacheSnapshot = File(appContext.cacheDir, SNAPSHOT_DIR_NAME)
        val journal = RestoreTransactionJournal(File(appContext.filesDir, RestoreTransactionJournal.FILE_NAME))
        runCatching { recoverState(liveRoot, durableSnapshot, legacyCacheSnapshot, journal) }
            .onFailure { Timber.e(it, "Restore startup recovery failed; failing closed") }
    }

    internal fun recoverState(
        liveRoot: File,
        durableSnapshot: File,
        legacyCacheSnapshot: File,
        journal: RestoreTransactionJournal,
    ) {
        when (journal.read()) {
            RestoreTransactionJournal.Phase.COMMITTED -> {
                // New data is authoritative; a leftover snapshot is cleanup debris, not a rollback.
                durableSnapshot.deleteRecursively()
                legacyCacheSnapshot.deleteRecursively()
                journal.clear()
                Timber.i("Recovered an interrupted restore: committed root kept, leftover snapshot removed")
            }
            RestoreTransactionJournal.Phase.OLD_ROOT_MOVED -> {
                // The snapshot is the only rollback copy, so it must be confirmed present before
                // anything is deleted — a missing snapshot with an intact root must fail closed,
                // never remove the root.
                check(durableSnapshot.exists()) { "Restore died mid-swap and the snapshot is missing; failing closed" }
                if (liveRoot.exists()) {
                    check(liveRoot.deleteRecursively()) { "Could not remove the half-installed restore root" }
                }
                check(durableSnapshot.renameTo(liveRoot) || copyBack(durableSnapshot, liveRoot)) {
                    "Could not restore the pre-restore snapshot; failing closed"
                }
                durableSnapshot.deleteRecursively()
                journal.clear()
                Timber.w("Recovered an interrupted restore: pre-restore snapshot moved back into place")
            }
            RestoreTransactionJournal.Phase.PREPARED -> {
                if (!liveRoot.exists()) {
                    check(durableSnapshot.isDirectory) { "Prepared restore has no live root or snapshot" }
                    // Persist the recovery phase before copying, so another crash cannot make a
                    // partial copy look like the intact pre-swap root.
                    journal.write(RestoreTransactionJournal.Phase.OLD_ROOT_MOVED)
                    recoverState(liveRoot, durableSnapshot, legacyCacheSnapshot, journal)
                } else {
                    if (durableSnapshot.exists()) {
                        check(durableSnapshot.listFiles()?.isEmpty() == true) { "Prepared restore has ambiguous roots" }
                        check(durableSnapshot.delete()) { "Could not remove the empty restore snapshot" }
                    }
                    journal.clear()
                    Timber.i("Recovered an interrupted restore: it died before the swap; live root untouched")
                }
            }
            null -> recoverLegacyState(liveRoot, durableSnapshot, legacyCacheSnapshot)
        }
    }

    /** Pre-journal builds left the snapshot under cacheDir with no phase record. */
    private fun recoverLegacyState(
        liveRoot: File,
        durableSnapshot: File,
        legacyCacheSnapshot: File,
    ) {
        if (durableSnapshot.exists()) durableSnapshot.deleteRecursively()
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
}
