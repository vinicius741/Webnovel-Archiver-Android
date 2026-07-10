package com.vinicius741.webnovelarchiver.data.storage

import java.io.File

internal interface RestoreFileOperations {
    fun rename(
        source: File,
        destination: File,
    ): Boolean = source.renameTo(destination)

    fun copyTree(
        source: File,
        destination: File,
    ) {
        check(source.copyRecursively(destination, overwrite = true)) {
            "Could not copy ${source.name} to ${destination.name}"
        }
    }

    fun deleteTree(file: File): Boolean = file.deleteRecursively()
}

private object DefaultRestoreFileOperations : RestoreFileOperations

/** Filesystem transaction primitive used by full restore and fault-injected JVM tests. */
internal class RestoreRootSwap(
    private val files: RestoreFileOperations = DefaultRestoreFileOperations,
) {
    fun swap(
        source: File,
        liveRoot: File,
        snapshot: File,
        initializeRoot: () -> Unit,
    ) {
        check(liveRoot.exists()) { "Live storage root is missing before restore" }
        check(source.exists()) { "Staged restore root is missing" }
        if (snapshot.exists()) {
            val contents = snapshot.listFiles() ?: error("Could not inspect the previous restore snapshot")
            check(contents.isEmpty()) {
                "A previous restore snapshot still requires recovery"
            }
            check(files.deleteTree(snapshot)) { "Could not clear the previous empty restore snapshot" }
            check(!snapshot.exists()) { "Could not clear the previous restore snapshot" }
        }
        check(files.rename(liveRoot, snapshot)) { "Could not create the required pre-restore snapshot" }
        check(snapshot.exists() && !liveRoot.exists()) {
            "Pre-restore snapshot rename did not complete"
        }

        if (files.rename(source, liveRoot)) {
            check(liveRoot.exists() && !source.exists()) { "Restore root rename did not complete" }
        } else {
            check(!liveRoot.exists()) { "Restore rename failed after creating a partial live root" }
            files.copyTree(source, liveRoot)
            check(liveRoot.exists()) { "Restore copy did not create the live root" }
            check(files.deleteTree(source) && !source.exists()) { "Could not remove copied restore staging root" }
        }
        initializeRoot()
        check(liveRoot.exists()) { "Live storage root is missing after restore initialization" }
        // Snapshot cleanup happens after the new live root is fully committed. A cleanup failure
        // must not turn that successful commit into a reported restore failure: the caller would
        // then attempt rollback from a snapshot that deleteRecursively may already have partially
        // removed. Keep any remainder for recovery and let the next restore fail closed.
        files.deleteTree(snapshot)
    }

    fun rollback(
        liveRoot: File,
        snapshot: File,
        initializeRoot: () -> Unit,
    ): Boolean {
        if (!snapshot.exists()) return false
        return runCatching {
            val contents = snapshot.listFiles() ?: error("Could not inspect the restore snapshot")
            check(contents.isNotEmpty()) { "Restore snapshot is empty" }
            if (liveRoot.exists()) {
                check(files.deleteTree(liveRoot) && !liveRoot.exists()) {
                    "Could not remove the incomplete live root"
                }
            }
            if (files.rename(snapshot, liveRoot)) {
                check(liveRoot.exists() && !snapshot.exists()) { "Snapshot rollback rename did not complete" }
            } else {
                check(!liveRoot.exists()) { "Rollback rename failed after creating a partial live root" }
                files.copyTree(snapshot, liveRoot)
                check(liveRoot.exists()) { "Snapshot rollback copy did not create the live root" }
            }
            initializeRoot()
            check(liveRoot.exists()) { "Live storage root is missing after rollback initialization" }
            if (snapshot.exists()) {
                check(files.deleteTree(snapshot) && !snapshot.exists()) {
                    "Could not remove the snapshot after rollback"
                }
            }
        }.isSuccess
    }
}
