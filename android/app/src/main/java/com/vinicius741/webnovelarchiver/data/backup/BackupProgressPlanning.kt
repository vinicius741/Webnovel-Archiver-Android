package com.vinicius741.webnovelarchiver.data.backup

/**
 * Progress message rules for the full-backup zip stream, shared by the exporter and its tests.
 * Messages reach the UI through a callback so no Android types are needed here.
 */
object BackupProgressPlanning {
    private const val REPORT_EVERY = 25

    fun startMessage(novelCount: Int): String = "Backing up $novelCount novels…"

    fun fileMessage(
        filesWritten: Int,
        totalFiles: Int,
    ): String = "Zipping files $filesWritten of $totalFiles"

    /** The zip loop is silent except at the first file, every REPORT_EVERY-th, and the last. */
    fun shouldReport(
        filesWritten: Int,
        totalFiles: Int,
    ): Boolean = filesWritten == totalFiles || filesWritten == 1 || filesWritten % REPORT_EVERY == 0
}
