package com.vinicius741.webnovelarchiver.data.storage

import com.vinicius741.webnovelarchiver.data.diagnostics.DiagnosticJournal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/*
 * R07: a process death between root moves bypasses every in-process catch/finally. These tests
 * simulate each kill point by leaving the journal at that phase (exactly what the swap writes
 * before dying) and running the production recovery state machine.
 */
class RestoreStartupRecoveryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun state(
        journalPhase: RestoreTransactionJournal.Phase?,
        liveContent: String?, // null = no live root
        durableSnapshotContent: String? = null, // null = no snapshot
        legacyCacheSnapshotContent: String? = null,
    ): RestoreTransactionJournal {
        val liveRoot = File(tmp.root, "live")
        liveContent?.let {
            liveRoot.mkdirs()
            File(liveRoot, it).writeText("data")
        }
        val durable = File(tmp.root, "durable_snapshot")
        durableSnapshotContent?.let {
            durable.mkdirs()
            File(durable, it).writeText("old")
        }
        val legacy = File(tmp.root, "cache_snapshot")
        legacyCacheSnapshotContent?.let {
            legacy.mkdirs()
            File(legacy, it).writeText("old")
        }
        val journalFile = File(tmp.root, RestoreTransactionJournal.FILE_NAME)
        val journal = RestoreTransactionJournal(journalFile)
        journalPhase?.let(journal::write)
        RestoreStartupRecovery.recoverState(liveRoot, durable, legacy, journal)
        return journal
    }

    @Test
    fun committedPhaseKeepsNewRootAndNeverRestoresLeftoverSnapshot() {
        val journal =
            state(
                journalPhase = RestoreTransactionJournal.Phase.COMMITTED,
                liveContent = "new_library.json",
                durableSnapshotContent = "old_library.json",
            )

        assertEquals("new_library.json", File(tmp.root, "live").listFiles()!!.single().name)
        assertFalse(File(tmp.root, "durable_snapshot").exists())
        assertTrue(journal.read() == null)
    }

    @Test
    fun oldRootMovedPhaseRestoresSnapshotWhenLiveRootIsMissing() {
        val journal =
            state(
                journalPhase = RestoreTransactionJournal.Phase.OLD_ROOT_MOVED,
                liveContent = null,
                durableSnapshotContent = "old_library.json",
            )

        assertEquals("old_library.json", File(tmp.root, "live").listFiles()!!.single().name)
        assertFalse(File(tmp.root, "durable_snapshot").exists())
        assertTrue(journal.read() == null)
    }

    @Test
    fun oldRootMovedPhaseReplacesHalfInstalledLiveRoot() {
        val journal =
            state(
                journalPhase = RestoreTransactionJournal.Phase.OLD_ROOT_MOVED,
                liveContent = "half_copied_new.json",
                durableSnapshotContent = "old_library.json",
            )

        // The half-installed copy is discarded; the snapshot is authoritative.
        assertEquals("old_library.json", File(tmp.root, "live").listFiles()!!.single().name)
        assertTrue(journal.read() == null)
    }

    @Test
    fun oldRootMovedPhaseWithIntactRootAndMissingSnapshotKeepsRootAndUnblocksRestore() {
        // Only a finished rollback move empties the snapshot during this phase, so the root is the
        // pre-restore library and only the journal clear was lost.
        val journal = state(RestoreTransactionJournal.Phase.OLD_ROOT_MOVED, liveContent = "old_library.json")

        assertEquals("old_library.json", File(tmp.root, "live").listFiles()!!.single().name)
        assertFalse(journal.exists())
    }

    @Test
    fun movedRootWithNothingLeftClearsJournalSoABackupCanBeRestored() {
        for (phase in listOf(RestoreTransactionJournal.Phase.OLD_ROOT_MOVED, RestoreTransactionJournal.Phase.PREPARED)) {
            File(tmp.root, "live").deleteRecursively()
            val journal = state(phase, liveContent = null)

            assertFalse(File(tmp.root, "live").exists())
            assertFalse("$phase journal must not block restore forever", journal.exists())
        }
    }

    @Test
    fun rolledBackPhaseKeepsRestoredRootAndDeletesHalfDeletedSnapshot() {
        val journal =
            state(
                journalPhase = RestoreTransactionJournal.Phase.ROLLED_BACK,
                liveContent = "old_library.json",
                durableSnapshotContent = "old_library_partial.json",
            )

        assertEquals("old_library.json", File(tmp.root, "live").listFiles()!!.single().name)
        assertFalse(File(tmp.root, "durable_snapshot").exists())
        assertFalse(journal.exists())
    }

    @Test
    fun rolledBackPhaseWithoutLiveRootPreservesSnapshot() {
        try {
            state(RestoreTransactionJournal.Phase.ROLLED_BACK, null, durableSnapshotContent = "original.json")
            fail("A missing rolled-back root must prevent snapshot cleanup")
        } catch (expected: IllegalStateException) {
            assertTrue(File(tmp.root, "durable_snapshot/original.json").isFile)
        }
    }

    @Test
    fun recoveryFailureRecordsDiagnosticsAndStillFailsClosed() {
        val diagnostics = DiagnosticJournal(File(tmp.root, "diagnostics"), background = false)
        DiagnosticJournal.active = diagnostics
        val liveRoot = File(tmp.root, "live")
        val snapshot = File(tmp.root, "durable_snapshot")
        snapshot.mkdirs()
        File(snapshot, "original.json").writeText("data")
        val journal = RestoreTransactionJournal(File(tmp.root, RestoreTransactionJournal.FILE_NAME))
        journal.write(RestoreTransactionJournal.Phase.COMMITTED)

        // The production entry point swallows the failure (startup must not crash) but records it.
        RestoreStartupRecovery.recoverSafely(liveRoot, snapshot, File(tmp.root, "cache_snapshot"), journal)

        // Fail closed: snapshot and journal stay untouched for manual recovery.
        assertTrue(File(snapshot, "original.json").isFile)
        assertEquals(RestoreTransactionJournal.Phase.COMMITTED, journal.read())
        // The error record keeps the exception; the operation record names the failed phase.
        val records = ArrayList<com.google.gson.JsonObject>().also { list -> diagnostics.forEachRecord(0L) { list += it } }
        DiagnosticJournal.active = null
        val error = records.single { it["k"].asString == "log" }
        assertEquals("E", error["level"].asString)
        assertEquals("java.lang.IllegalStateException", error.getAsJsonObject("error")["type"].asString)
        val operation = records.single { it["k"].asString == "op" }
        assertEquals("restore_recovery_failed_committed", operation["op"].asString)
        assertTrue(operation["failed"].asBoolean)
    }

    @Test
    fun preparedPhaseRestoresRootMovedBeforePhaseWrite() {
        val journal =
            state(
                journalPhase = RestoreTransactionJournal.Phase.PREPARED,
                liveContent = null,
                durableSnapshotContent = "old_library.json",
            )

        assertEquals("old_library.json", File(tmp.root, "live").listFiles()!!.single().name)
        assertFalse(File(tmp.root, "durable_snapshot").exists())
        assertTrue(journal.read() == null)
        RestoreStartupRecovery.recoverState(
            File(tmp.root, "live"),
            File(tmp.root, "durable_snapshot"),
            File(tmp.root, "cache_snapshot"),
            journal,
        )
        assertTrue(File(tmp.root, "live/old_library.json").isFile)
    }

    @Test
    fun preparedPhaseKeepsIntactLiveRootAndClearsJournal() {
        val journal =
            state(
                journalPhase = RestoreTransactionJournal.Phase.PREPARED,
                liveContent = "old_library.json",
                durableSnapshotContent = null,
            )

        assertEquals("old_library.json", File(tmp.root, "live").listFiles()!!.single().name)
        assertTrue(journal.read() == null)
    }

    @Test
    fun noJournalWithLiveRootAndLegacySnapshotMeansCommitHadCompleted() {
        val journal =
            state(
                journalPhase = null,
                liveContent = "new_library.json",
                legacyCacheSnapshotContent = "old_library.json",
            )

        assertEquals("new_library.json", File(tmp.root, "live").listFiles()!!.single().name)
        assertFalse(File(tmp.root, "cache_snapshot").exists())
        assertTrue(journal.read() == null)
    }

    @Test
    fun noJournalWithoutLiveRootRestoresLegacySnapshot() {
        val journal =
            state(
                journalPhase = null,
                liveContent = null,
                legacyCacheSnapshotContent = "old_library.json",
            )

        assertEquals("old_library.json", File(tmp.root, "live").listFiles()!!.single().name)
        assertFalse(File(tmp.root, "cache_snapshot").exists())
    }

    @Test
    fun committedPhaseWithoutLiveRootPreservesSnapshot() {
        try {
            state(RestoreTransactionJournal.Phase.COMMITTED, null, durableSnapshotContent = "original.json")
            fail("A missing committed root must prevent snapshot cleanup")
        } catch (expected: IllegalStateException) {
            assertTrue(File(tmp.root, "durable_snapshot/original.json").isFile)
        }
    }

    @Test
    fun unreadableJournalPreservesBothRoots() {
        val journalFile = File(tmp.root, RestoreTransactionJournal.FILE_NAME)
        journalFile.writeText("unrecognized phase")
        try {
            state(null, "partial.json", durableSnapshotContent = "original.json")
            fail("An unreadable journal must prevent cleanup")
        } catch (expected: IllegalStateException) {
            assertTrue(File(tmp.root, "durable_snapshot/original.json").isFile)
            assertTrue(File(tmp.root, "live/partial.json").isFile)
            assertEquals("unrecognized phase", journalFile.readText())
        }
    }

    @Test
    fun missingJournalPreservesUnclassifiedDurableSnapshot() {
        try {
            state(null, null, durableSnapshotContent = "original.json")
            fail("A durable snapshot without a journal must not be deleted")
        } catch (expected: IllegalStateException) {
            assertTrue(File(tmp.root, "durable_snapshot/original.json").isFile)
            assertFalse(File(tmp.root, "live").exists())
        }
    }

    @Test
    fun noJournalNoSnapshotLeavesEverythingAlone() {
        state(journalPhase = null, liveContent = "library.json")

        assertEquals("library.json", File(tmp.root, "live").listFiles()!!.single().name)
    }
}
