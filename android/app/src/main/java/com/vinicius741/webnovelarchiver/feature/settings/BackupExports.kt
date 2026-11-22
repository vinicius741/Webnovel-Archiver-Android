package com.vinicius741.webnovelarchiver.feature.settings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** What a full-backup export needs from the app: the export itself, and how its outcome is shown. */
internal interface BackupExportHost {
    /** Writes a full backup to the app's backups folder, reporting progress lines (from any thread). */
    suspend fun export(onProgress: (String) -> Unit): File

    /** Offers the saved file (share or done); shown on whatever screen is open. */
    fun showSaved(file: File)

    fun showFailed(message: String)
}

/** A full backup in progress: its latest progress line, null before the first one. */
data class BackupExportProgress(
    val message: String? = null,
)

/**
 * The full-backup export, owned by the activity (like the Updates syncs) because it must outlive
 * Data & backup: leaving the screen, or Back to Settings, keeps it running, and returning shows its
 * progress again. One runs at a time. It ends with the activity's scope.
 */
class BackupExports internal constructor(
    private val host: BackupExportHost,
    private val scope: CoroutineScope,
) : BackupExportControl {
    private val mutableRunning = MutableStateFlow<BackupExportProgress?>(null)
    private val mutableFinished = MutableStateFlow(0)

    /** The export in progress, or null. */
    override val running: StateFlow<BackupExportProgress?> = mutableRunning.asStateFlow()

    /** Counts finished exports (saved or failed), so the backup files list can reload. */
    override val finished: StateFlow<Int> = mutableFinished.asStateFlow()

    /** Starts an export unless one is running; returns whether it started. */
    override fun start(): Boolean {
        if (mutableRunning.value != null) return false
        mutableRunning.value = BackupExportProgress()
        scope.launch {
            try {
                val file =
                    try {
                        host.export { message -> mutableRunning.update { running -> running?.copy(message = message) } }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (
                        @Suppress("TooGenericExceptionCaught") error: Exception,
                    ) {
                        host.showFailed(error.message ?: "Backup failed")
                        null
                    }
                file?.let(host::showSaved)
            } finally {
                mutableRunning.value = null
                mutableFinished.update { it + 1 }
            }
        }
        return true
    }
}
