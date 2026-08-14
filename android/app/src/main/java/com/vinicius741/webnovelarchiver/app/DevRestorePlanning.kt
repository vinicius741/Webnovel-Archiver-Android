package com.vinicius741.webnovelarchiver.app

import java.io.File

/**
 * Debug-only "dev full-backup restore" planning (agent QA convenience).
 *
 * Backs the `dev_restore_full_backup` intent extra: an agent stages a
 * `webnovel_full_backup_*.zip` inside the app's own cache (adb push + `run-as cp`, since the
 * app can't read arbitrary device storage) and cold-starts with the extra naming that file.
 * [MainActivity] (gated on `BuildConfig.DEBUG`, so this is dead in release) feeds the file to
 * the production restore pipeline (`repository.importFullBackupUri`) — same extraction,
 * staging, verification, and atomic root swap as the in-app Settings picker, plus a cache
 * refresh — then removes the staged zip so a later relaunch can never re-run the restore.
 *
 * Planning is pure and unit-testable, mirroring [DevLaunchPlanning] and
 * [DevLibraryReportPlanning].
 */
object DevRestorePlanning {
    /** Intent extra carrying the backup's path relative to the app's cacheDir (e.g. "dev_restore_source.zip"). */
    const val EXTRA_DEV_RESTORE_FULL_BACKUP = "dev_restore_full_backup"

    /**
     * Resolves the extra's value against [cacheDir]. Returns `null` for blank input, absolute
     * paths, or anything that canonicalizes outside [cacheDir] — the hook must never be able to
     * point at files the app couldn't already read, and a path escape must fail closed.
     */
    fun resolveSandboxZipPath(
        cacheDir: File,
        relative: String?,
    ): File? {
        val trimmed = relative?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (trimmed.startsWith("/")) return null
        val cacheRoot = cacheDir.canonicalFile
        val candidate = File(cacheDir, trimmed).canonicalFile
        if (candidate == cacheRoot || candidate.parentFile == null) return null
        return candidate.takeIf { it.canonicalPath.startsWith(cacheRoot.canonicalPath + File.separator) }
    }
}
