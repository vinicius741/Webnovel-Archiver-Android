package com.vinicius741.webnovelarchiver.data.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/*
 * Kills a full restore at every filesystem step — inside the swap, inside the in-process rollback,
 * and inside startup recovery itself — then runs startup recovery and requires the library to be
 * exactly the old tree or exactly the new tree, with no journal or snapshot left behind.
 *
 * Copies and deletes die between individual files, so half-copied roots and half-deleted
 * snapshots are part of the search. `renameIntoLive = false` forces every move into the live
 * root down the copy fallback, which is where non-atomic snapshot cleanup used to be unsafe.
 * `renameToCandidate = false` does the same for the pre-journal move of the staged tree (in the
 * filesDir work directory) to the swap candidate beside the live root.
 */
class RestoreCrashRecoveryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class ProcessDeath : Error("simulated process death")

    /** Counts every interruption point; the [dieAt]-th one kills the "process". */
    private class DyingFiles(
        private val dieAt: Int,
        private val live: File,
        private val renameIntoLive: Boolean,
        private val renameToCandidate: Boolean = true,
    ) : RestoreFileOperations {
        var points = 0
            private set
        var died = false
            private set

        private fun point() {
            if (died) throw ProcessDeath()
            if (points++ == dieAt) {
                died = true
                throw ProcessDeath()
            }
        }

        override fun rename(
            source: File,
            destination: File,
        ): Boolean {
            point()
            val candidate = destination.name.startsWith(RestoreStartupRecovery.SWAP_CANDIDATE_PREFIX)
            val allowed = if (candidate) renameToCandidate else renameIntoLive || destination != live
            val moved = allowed && source.renameTo(destination)
            point()
            return moved
        }

        override fun copyTree(
            source: File,
            destination: File,
        ) {
            point()
            source.walkTopDown().forEach { file ->
                val target = File(destination, file.relativeTo(source).path)
                if (file.isDirectory) target.mkdirs() else file.copyTo(target, overwrite = true)
                point()
            }
        }

        override fun deleteTree(file: File): Boolean {
            point()
            file.walkBottomUp().forEach { entry ->
                entry.delete()
                point()
            }
            return !file.exists()
        }
    }

    private val live get() = File(tmp.root, "live")
    private val snapshot get() = File(tmp.root, "snapshot")
    private val staged get() = File(tmp.root, "staged")
    private val journal get() = RestoreTransactionJournal(File(tmp.root, RestoreTransactionJournal.FILE_NAME))

    private val oldTree = mapOf("library.json" to "old", "novels/a/1.html" to "old-a1", "novels/b/1.html" to "old-b1")
    private val newTree = mapOf("library.json" to "new", "novels/c/1.html" to "new-c1", "covers/c.jpg" to "new-cover")

    /** The restore work directory in filesDir ([tmp] root); the swap candidate goes beside [live]. */
    private val restoreWork get() = File(tmp.root, "webnovel_restore_work")
    private val workStaged get() = File(restoreWork, "1/staged_root")

    private fun reset(stagedRoot: File = staged) {
        tmp.root
            .listFiles()
            .orEmpty()
            .forEach { it.deleteRecursively() }
        write(live, oldTree)
        write(stagedRoot, newTree)
    }

    private fun write(
        root: File,
        tree: Map<String, String>,
    ) {
        tree.forEach { (path, text) -> File(root, path).apply { parentFile?.mkdirs() }.writeText(text) }
    }

    private fun contents(root: File): Map<String, String> =
        root
            .walkTopDown()
            .filter(File::isFile)
            .associate { it.relativeTo(root).path to it.readText() }

    private enum class Failure { NONE, INITIALIZATION }

    /**
     * Runs one restore attempt the way FullBackupRestorer does (commit, rollback on an ordinary
     * failure), but a [ProcessDeath] stops everything. Returns true when the commit completed.
     */
    private fun attempt(
        files: DyingFiles,
        failure: Failure,
    ): Boolean {
        var initializations = 0
        val committer =
            RestoreRootCommitter(live, snapshot, journal, {
                initializations += 1
                check(failure != Failure.INITIALIZATION || initializations > 1) { "Injected initialization failure" }
                live.mkdirs()
            }, RestoreRootSwap(files))
        return try {
            committer.commit(staged)
            true
        } catch (ignored: ProcessDeath) {
            false
        } catch (ignored: IllegalStateException) {
            committer.rollback()
            false
        }
    }

    private fun assertResolved(
        label: String,
        committed: Boolean,
    ) {
        val result = contents(live)
        assertTrue("$label: live root is neither the old nor the new library: $result", result == oldTree || result == newTree)
        if (committed) assertEquals("$label: a completed commit must keep the new library", newTree, result)
        assertFalse("$label: snapshot left behind", snapshot.exists())
        assertFalse("$label: journal left behind", journal.exists())
    }

    private fun forEachKillPoint(block: (Int) -> DyingFiles) {
        var dieAt = 0
        while (true) {
            val files = block(dieAt)
            if (!files.died) return
            dieAt += 1
            check(dieAt < MAX_POINTS) { "Kill-point search did not terminate" }
        }
    }

    @Test
    fun deathAtAnySwapOrRollbackStepRecoversToOneWholeLibrary() {
        for (renameIntoLive in listOf(true, false)) {
            for (failure in Failure.entries) {
                forEachKillPoint { dieAt ->
                    reset()
                    val files = DyingFiles(dieAt, live, renameIntoLive)
                    val committed = attempt(files, failure)
                    RestoreStartupRecovery.recoverState(live, snapshot, journal)
                    assertResolved("rename=$renameIntoLive failure=$failure dieAt=$dieAt", committed)
                    if (failure == Failure.INITIALIZATION) {
                        assertEquals("rollback must keep the old library", oldTree, contents(live))
                    }
                    files
                }
            }
        }
    }

    @Test
    fun deathInsideStartupRecoveryIsRecoveredByTheNextStartup() {
        for (renameIntoLive in listOf(true, false)) {
            for (failure in Failure.entries) {
                forEachKillPoint { restoreDieAt ->
                    val restoreFiles = DyingFiles(restoreDieAt, live, renameIntoLive)
                    forEachKillPoint { recoveryDieAt ->
                        reset()
                        val committed = attempt(DyingFiles(restoreDieAt, live, renameIntoLive), failure)
                        val recoveryFiles = DyingFiles(recoveryDieAt, live, renameIntoLive)
                        try {
                            RestoreStartupRecovery.recoverState(live, snapshot, journal, recoveryFiles)
                        } catch (ignored: ProcessDeath) {
                            // The next startup runs recovery again.
                        } catch (ignored: IllegalStateException) {
                            // A failed move inside recovery fails closed; the next startup retries.
                        }
                        RestoreStartupRecovery.recoverState(live, snapshot, journal)
                        assertResolved(
                            "rename=$renameIntoLive failure=$failure restoreDieAt=$restoreDieAt recoveryDieAt=$recoveryDieAt",
                            committed,
                        )
                        recoveryFiles
                    }
                    reset()
                    attempt(restoreFiles, failure)
                    restoreFiles
                }
            }
        }
    }

    /**
     * The whole restore from a staged root in the work directory: the pre-journal move beside the live root
     * (a rename, or the verified-copy fallback), then the swap. After a death or failure anywhere,
     * the next startup's recovery and staging sweep must leave one whole library and no debris.
     */
    @Test
    fun deathWhileStagingBesideTheLiveRootLeavesOneLibraryAndNoDebris() {
        for (renameToCandidate in listOf(true, false)) {
            for (renameIntoLive in listOf(true, false)) {
                forEachKillPoint { dieAt ->
                    reset(workStaged)
                    val files = DyingFiles(dieAt, live, renameIntoLive, renameToCandidate)
                    val committer = RestoreRootCommitter(live, snapshot, journal, { live.mkdirs() }, RestoreRootSwap(files))
                    var staging = true
                    val committed =
                        try {
                            val candidate = committer.stageBesideLiveRoot(workStaged)
                            staging = false
                            committer.commit(candidate)
                            true
                        } catch (ignored: ProcessDeath) {
                            false
                        } catch (ignored: IllegalStateException) {
                            committer.rollback()
                            false
                        }
                    val label = "candidateRename=$renameToCandidate liveRename=$renameIntoLive dieAt=$dieAt"
                    if (staging) {
                        assertEquals("$label: staging must not touch the live root", oldTree, contents(live))
                        assertFalse("$label: staging must not write a journal", journal.exists())
                    }
                    RestoreStartupRecovery.recoverState(live, snapshot, journal)
                    if (!journal.exists()) RestoreStartupRecovery.sweepOrphanedStaging(tmp.root, restoreWork)
                    assertResolved(label, committed)
                    val debris = tmp.root.list()!!.filter { it.startsWith(RestoreStartupRecovery.SWAP_CANDIDATE_PREFIX) }
                    assertTrue("$label: swap candidate left behind: $debris", debris.isEmpty())
                    assertTrue("$label: restore work left behind", restoreWork.list().orEmpty().isEmpty())
                    files
                }
            }
        }
    }

    @Test
    fun startupSweepsOrphanedStagingOnlyFromKilledRestores() {
        val filesDir = File(tmp.root, "files").apply { mkdirs() }
        val workDir = File(filesDir, "webnovel_restore_work").apply { mkdirs() }
        write(File(filesDir, "${RestoreStartupRecovery.SWAP_CANDIDATE_PREFIX}123"), newTree)
        write(File(filesDir, "webnovel_archiver"), oldTree)
        write(File(workDir, "456/raw_extraction"), newTree)

        RestoreStartupRecovery.sweepOrphanedStaging(filesDir, workDir)

        assertEquals(listOf("webnovel_archiver", "webnovel_restore_work"), filesDir.list()!!.sorted())
        assertEquals(oldTree, contents(File(filesDir, "webnovel_archiver")))
        assertTrue(workDir.isDirectory && workDir.list()!!.isEmpty())
    }

    private companion object {
        const val MAX_POINTS = 500
    }
}
