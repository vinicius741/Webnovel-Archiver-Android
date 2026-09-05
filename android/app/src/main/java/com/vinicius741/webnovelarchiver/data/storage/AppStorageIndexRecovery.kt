package com.vinicius741.webnovelarchiver.data.storage

import com.vinicius741.webnovelarchiver.domain.model.Story
import timber.log.Timber
import java.io.File

/*
 * Library-index read + corruption recovery, split out of [AppStorage] to keep that class inside
 * its file-size budget. Runs under the storage monitor like every other document access.
 */

@Synchronized
internal fun AppStorage.readLibraryIdsWithRecovery(): List<String> {
    val result = DurableJson.readAtomicResult<List<String>>(libraryIndex, gson)
    if (result is DurableReadResult.Present) {
        clearStorageIssues(libraryIndex)
        return result.value.filter { it.isNotBlank() }.distinct()
    }

    when (result) {
        is DurableReadResult.Corrupt ->
            recordStorageIssue(libraryIndex, StorageHealthKind.Corrupt, "Library index was corrupt and quarantined")
        is DurableReadResult.UnsupportedSchema ->
            recordStorageIssue(
                libraryIndex,
                StorageHealthKind.UnsupportedSchema,
                "Library index schema ${result.foundVersion} is unsupported",
            )
        is DurableReadResult.IoFailure ->
            recordStorageIssue(libraryIndex, StorageHealthKind.IoFailure, result.cause.message ?: "I/O failure")
        DurableReadResult.Absent -> Unit
        is DurableReadResult.Present -> Unit
    }

    val storyFiles = storyDir.listFiles()?.toList().orEmpty()
    if (storyFiles.none { it.isFile && it.name.endsWith(".json") }) return emptyList()
    val recovery =
        LibraryIndexRecovery.scan(
            files = storyFiles,
            safeName = ::safeName,
            readStory = { file ->
                DurableJson.readAtomicResult<Story>(file, gson, quarantineOnCorruption = false).also { storyResult ->
                    when (storyResult) {
                        is DurableReadResult.Corrupt ->
                            recordStorageIssue(file, StorageHealthKind.Corrupt, "Story document is corrupt and was left untouched")
                        is DurableReadResult.UnsupportedSchema ->
                            recordStorageIssue(file, StorageHealthKind.UnsupportedSchema, "Story schema is unsupported")
                        is DurableReadResult.IoFailure ->
                            recordStorageIssue(file, StorageHealthKind.IoFailure, storyResult.cause.message ?: "I/O failure")
                        is DurableReadResult.Present -> clearStorageIssues(file)
                        DurableReadResult.Absent -> Unit
                    }
                }
            },
        )
    val recoveredIds = recovery.stories.map { it.id }
    // Persist a rebuilt index for recoverable cases so cold starts stop re-scanning. Leave an
    // UnsupportedSchema index untouched so a downgrade cannot clobber a newer on-disk shape.
    if (result !is DurableReadResult.UnsupportedSchema) {
        persistRecoveredLibraryIndex(recoveredIds)
    } else {
        recordStorageIssue(
            libraryIndex,
            StorageHealthKind.LibraryIndexRecovered,
            "Reconstructed an in-memory library index from valid story documents; unsupported index was not rewritten",
            recovery.stories.size,
        )
    }
    return recoveredIds
}

@Synchronized
internal fun AppStorage.persistRecoveredLibraryIndex(ids: List<String>) {
    // Drop sticky IoFailure/Corrupt fences for the index so intentional recovery can rewrite it.
    clearStorageIssues(libraryIndex)
    runCatching {
        maintenanceCoordinator.withStorageAccess(this) {
            DurableJson.writeAtomic(libraryIndex, gson, DurableJson.envelope(ids, appVersion))
        }
        recordStorageIssue(
            libraryIndex,
            StorageHealthKind.LibraryIndexRecovered,
            "Reconstructed and persisted library index from valid story documents",
            ids.size,
        )
    }.onFailure { error ->
        Timber.e(error, "Could not persist recovered library index")
        recordStorageIssue(
            libraryIndex,
            StorageHealthKind.LibraryIndexRecovered,
            "Reconstructed an in-memory library index from valid story documents; persistence failed",
            ids.size,
        )
        recordStorageIssue(libraryIndex, StorageHealthKind.IoFailure, error.message ?: "I/O failure")
    }
}
