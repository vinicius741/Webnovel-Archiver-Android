package com.vinicius741.webnovelarchiver.data.repository

import com.vinicius741.webnovelarchiver.data.backup.BackupFilePlanning
import java.io.File

/** File metadata captured under the repository's storage lock and I/O dispatcher. */
internal data class BackupFileEntry(
    val file: File,
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
)

internal suspend fun AppRepository.listBackupFiles(): List<BackupFileEntry> =
    storageTransaction {
        BackupFilePlanning
            .listArtifacts(storage.backupRoot)
            .map { file ->
                BackupFileEntry(
                    file = file,
                    sizeBytes = file.length(),
                    lastModifiedMillis = file.lastModified(),
                )
            }
    }

internal suspend fun AppRepository.deleteBackupFile(file: File): Boolean =
    storageTransaction { BackupFilePlanning.deleteWithin(storage.backupRoot, file) }
