package com.vinicius741.webnovelarchiver.data.storage

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
    fun oldRootMovedPhaseWithIntactRootAndMissingSnapshotFailsClosedWithoutDeletingRoot() {
        val liveRoot = File(tmp.root, "live")
        liveRoot.mkdirs()
        File(liveRoot, "old_library.json").writeText("data")
        val journal = RestoreTransactionJournal(File(tmp.root, RestoreTransactionJournal.FILE_NAME))
        journal.write(RestoreTransactionJournal.Phase.OLD_ROOT_MOVED)

        try {
            RestoreStartupRecovery.recoverState(
                liveRoot,
                File(tmp.root, "durable_snapshot"),
                File(tmp.root, "cache_snapshot"),
                journal,
            )
            fail("Expected recovery to fail closed when the snapshot is missing")
        } catch (expected: IllegalStateException) {
            // Fail closed: the journal stays for manual recovery.
        }

        assertEquals("old_library.json", liveRoot.listFiles()!!.single().name)
        assertEquals(RestoreTransactionJournal.Phase.OLD_ROOT_MOVED, journal.read())
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
    fun noJournalNoSnapshotLeavesEverythingAlone() {
        state(journalPhase = null, liveContent = "library.json")

        assertEquals("library.json", File(tmp.root, "live").listFiles()!!.single().name)
    }
}
