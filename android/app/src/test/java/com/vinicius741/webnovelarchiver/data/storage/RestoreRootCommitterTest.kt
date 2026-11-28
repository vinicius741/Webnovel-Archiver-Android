package com.vinicius741.webnovelarchiver.data.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class RestoreRootCommitterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val live get() = File(tmp.root, "live")
    private val snapshot get() = File(tmp.root, "snapshot")
    private val journal get() = RestoreTransactionJournal(File(tmp.root, "journal"))

    private fun committer(files: RestoreFileOperations = object : RestoreFileOperations {}) =
        RestoreRootCommitter(live, snapshot, journal, { live.mkdirs() }, RestoreRootSwap(files))

    private fun seed(
        directory: File,
        name: String,
    ): File =
        directory.apply {
            mkdirs()
            File(this, name).writeText(name)
        }

    @Test
    fun validationFailureDoesNotRollBackAnEarlierSnapshot() {
        seed(live, "current")
        seed(snapshot, "old")
        journal.write(RestoreTransactionJournal.Phase.COMMITTED)
        assertTrue(committer().rollback())
        assertTrue(File(live, "current").isFile)
        assertTrue(File(snapshot, "old").isFile)
        assertEquals(RestoreTransactionJournal.Phase.COMMITTED, journal.read())
    }

    @Test
    fun commitRefusesToReplaceAnUnresolvedJournal() {
        seed(live, "current")
        seed(snapshot, "old")
        journal.write(RestoreTransactionJournal.Phase.COMMITTED)
        val committer = committer()
        assertTrue(runCatching { committer.commit(seed(File(tmp.root, "staged"), "new")) }.isFailure)
        assertTrue(committer.rollback())
        assertTrue(File(live, "current").isFile)
        assertEquals(RestoreTransactionJournal.Phase.COMMITTED, journal.read())
    }

    @Test
    fun failedFirstRenameClearsOnlyThisAttemptsPreparedJournal() {
        seed(live, "current")
        val committer =
            committer(
                object : RestoreFileOperations {
                    override fun rename(
                        source: File,
                        destination: File,
                    ): Boolean = false
                },
            )
        assertTrue(runCatching { committer.commit(seed(File(tmp.root, "staged"), "new")) }.isFailure)
        assertTrue(committer.rollback())
        assertFalse(journal.exists())
        assertTrue(File(live, "current").isFile)
    }

    @Test
    fun failedInitializationRollsBackThisAttemptsSnapshot() {
        seed(live, "current")
        var attempts = 0
        val committer =
            RestoreRootCommitter(live, snapshot, journal, {
                attempts += 1
                check(attempts > 1) { "Injected initialization failure" }
            })
        assertTrue(runCatching { committer.commit(seed(File(tmp.root, "staged"), "new")) }.isFailure)
        assertTrue(committer.rollback())
        assertTrue(File(live, "current").isFile)
        assertFalse(File(live, "new").exists())
        assertFalse(journal.exists())
    }

    @Test
    fun incompleteCleanupKeepsCommittedJournalAndNeverRollsBack() {
        seed(live, "current")
        val committer =
            committer(
                object : RestoreFileOperations {
                    override fun deleteTree(file: File): Boolean = if (file == snapshot) false else file.deleteRecursively()
                },
            )
        committer.commit(seed(File(tmp.root, "staged"), "new"))
        assertEquals(RestoreTransactionJournal.Phase.COMMITTED, journal.read())
        assertTrue(committer.rollback())
        assertTrue(File(live, "new").isFile)
        RestoreStartupRecovery.recoverState(live, snapshot, journal)
        assertTrue(File(live, "new").isFile)
        assertFalse(snapshot.exists())
        assertFalse(journal.exists())
    }

    @Test
    fun stagingBesideLiveRootRenamesTheTreeInsteadOfCopyingIt() {
        seed(live, "current")
        val staged = seed(File(tmp.root, "cache/work/staged_root"), "new")
        val inode = Files.getAttribute(File(staged, "new").toPath(), "unix:ino")

        val candidate = committer().stageBesideLiveRoot(staged)

        assertEquals(live.parentFile, candidate.parentFile)
        assertTrue(candidate.name.startsWith(RestoreStartupRecovery.SWAP_CANDIDATE_PREFIX))
        assertFalse(staged.exists())
        assertEquals(inode, Files.getAttribute(File(candidate, "new").toPath(), "unix:ino"))
        assertTrue(File(live, "current").isFile)
        assertFalse(journal.exists())
    }

    @Test
    fun stagingFallsBackToAVerifiedCopyWhenTheRenameIsRefused() {
        seed(live, "current")
        val staged = seed(File(tmp.root, "cache/work/staged_root"), "new")
        val committer =
            committer(
                object : RestoreFileOperations {
                    override fun rename(
                        source: File,
                        destination: File,
                    ): Boolean = !destination.name.startsWith(RestoreStartupRecovery.SWAP_CANDIDATE_PREFIX) && source.renameTo(destination)
                },
            )

        val candidate = committer.stageBesideLiveRoot(staged)
        committer.commit(candidate)

        assertEquals("new", File(live, "new").readText())
        assertFalse(staged.exists())
        assertFalse(candidate.exists())
    }

    @Test
    fun committedRestoreInvalidatesCoverCacheKeys() {
        seed(live, "current")
        val cover = File(live, "covers/story.jpg")
        val previousKey = LocalImageRevision.current(cover)

        committer().commit(seed(File(tmp.root, "staged"), "new"))

        assertTrue(previousKey != LocalImageRevision.current(cover))
    }
}
