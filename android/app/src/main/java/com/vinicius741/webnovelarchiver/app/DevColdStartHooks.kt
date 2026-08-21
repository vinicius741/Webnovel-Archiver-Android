package com.vinicius741.webnovelarchiver.app

import android.net.Uri
import com.vinicius741.webnovelarchiver.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

// Debug-only cold-start hooks used by agent QA (see android/AGENTS.md): full-backup restore from a
// zip staged in the app cache and the post-hydration library report. Both are no-ops in release —
// callers gate them on BuildConfig.DEBUG.

/**
 * Restores a full-backup ZIP staged inside the app's own cache (pushed there with adb + `run-as cp`)
 * through the production pipeline, which also refreshes the repository cache. The staged zip is
 * removed afterwards so a later relaunch can never re-run the restore. Returns true when a restore
 * was requested (even if it failed — the report and the log line then say so). See
 * [DevRestorePlanning] for the extra and the path contract.
 */
internal suspend fun MainActivity.maybeRestoreFullBackupForDev(): Boolean {
    val zip =
        DevRestorePlanning.resolveSandboxZipPath(
            cacheDir,
            intent.getStringExtra(DevRestorePlanning.EXTRA_DEV_RESTORE_FULL_BACKUP),
        ) ?: return false
    try {
        if (!zip.isFile) {
            Timber.e("Dev full-backup restore: %s not found in cacheDir", zip.path)
            return true
        }
        val summary = repository.importFullBackupUri(Uri.fromFile(zip))
        Timber.i("Dev full-backup restore: %s", summary)
    } finally {
        if (!zip.delete()) Timber.w("Dev full-backup restore: could not remove staged zip %s", zip.name)
    }
    return true
}

/**
 * Snapshots the hydrated library (what the storage layer actually parsed, including quarantine
 * events) to `cache/dev_library_report.json` for agent verification of restores/imports.
 * Debug-launch-only; see [DevLibraryReportPlanning] for the extra and the report contract.
 */
internal suspend fun MainActivity.writeDevLibraryReport() {
    withContext(Dispatchers.IO) {
        runCatching {
            val report =
                DevLibraryReportPlanning.build(
                    library = repository.library(),
                    tabs = repository.getTabs(),
                    storageIssues = repository.getStorageHealth().issues,
                    appVersion = BuildConfig.VERSION_NAME,
                )
            val output = File(cacheDir, DevLibraryReportPlanning.REPORT_FILENAME)
            output.writeText(DevLibraryReportPlanning.toJson(report))
            Timber.i(
                "Dev library report: %d stories, ids sha256 %s, %d storage issue(s) -> %s",
                report.librarySize,
                report.storyIdsSha256,
                report.storageIssues.size,
                output.absolutePath,
            )
        }.onFailure { error -> Timber.e(error, "Dev library report failed") }
    }
}
