package com.vinicius741.webnovelarchiver.data.storage

import android.net.Uri
import java.io.File

/**
 * Process-wide entry point for backup and restore operations.
 *
 * The coordinator owns the maintenance boundary; format-specific I/O lives in focused collaborators
 * so the lock cannot accidentally be bypassed as those implementations evolve.
 */
class BackupRestoreCoordinator(
    private val storage: AppStorage,
) {
    private val exporter by lazy { BackupExporter(storage) }
    private val jsonImporter by lazy { JsonBackupImporter(storage) }
    private val fullRestorer by lazy { FullBackupRestorer(storage) }

    fun exportBackup(): File = runMaintenance(MaintenanceOperation.ExportJson, exporter::exportJson)

    fun exportCleanupRules(): File = runMaintenance(MaintenanceOperation.ExportCleanupRules, exporter::exportCleanupRules)

    fun exportFullBackup(): File = runMaintenance(MaintenanceOperation.ExportFull, exporter::exportFull)

    fun importBackupUri(uri: Uri): String = runMaintenance(MaintenanceOperation.ImportJson) { jsonImporter.import(uri) }

    fun importFullBackupUri(uri: Uri): String = runMaintenance(MaintenanceOperation.RestoreFull) { fullRestorer.import(uri) }

    private fun <T> runMaintenance(
        operation: MaintenanceOperation,
        block: () -> T,
    ): T = storage.maintenanceCoordinator.runExclusive(storage, operation, block)
}
