package com.vinicius741.webnovelarchiver.feature.settings

import com.vinicius741.webnovelarchiver.data.repository.BackupFileEntry
import com.vinicius741.webnovelarchiver.navigation.ScreenModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/** What Data & backup reads and changes besides the export. */
internal interface DataBackupData {
    suspend fun backupFiles(): List<BackupFileEntry>

    suspend fun deleteBackupFile(file: File): Boolean

    /** Storage problems found at startup, one line each ("document: detail"); empty when healthy. */
    fun storageIssues(): List<String>

    /** Deletes every novel, chapter and setting on this phone (backups stay). */
    suspend fun clearAll()
}

/** The full-backup export as Data & backup sees it ([BackupExports]). */
interface BackupExportControl {
    val running: StateFlow<BackupExportProgress?>
    val finished: StateFlow<Int>

    fun start(): Boolean
}

internal sealed interface BackupFilesState {
    data object Loading : BackupFilesState

    data class Loaded(
        val files: List<BackupFileEntry>,
    ) : BackupFilesState

    data object Failed : BackupFilesState
}

internal data class DataBackupUiState(
    /** The full backup in progress, or null. */
    val export: BackupExportProgress?,
    val files: BackupFilesState,
    val storageIssues: List<String>,
) {
    /** What the page is built from; a new progress line only patches the progress card. */
    val buildKey: Any get() = Triple(export != null, files, storageIssues)
}

/**
 * Data & backup (`AppRoute.DataBackup`): the backup files kept on this phone, storage problems, and
 * the export in progress, which runs in the activity-level [BackupExportControl] so it outlives the
 * screen. While a renderer collects [state] it follows the export, and reloads the files when one
 * ends.
 */
internal class DataBackupScreenModel(
    private val data: DataBackupData,
    private val exports: BackupExportControl,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : ScreenModel(scope) {
    private val mutableState = MutableStateFlow(DataBackupUiState(exports.running.value, BackupFilesState.Loading, data.storageIssues()))
    val state: StateFlow<DataBackupUiState> = mutableState.asStateFlow()

    init {
        this.scope.launch {
            mutableState.subscriptionCount
                .map { it > 0 }
                .distinctUntilChanged()
                .collectLatest { observed -> if (observed) follow() }
        }
    }

    /** Re-reads the storage problems and the backup files (shown again on each render). */
    fun refresh() {
        mutableState.update { it.copy(export = exports.running.value, storageIssues = data.storageIssues()) }
        scope.launch { loadFiles() }
    }

    /** Starts a full backup unless one runs. */
    fun startExport() {
        exports.start()
        mutableState.update { it.copy(export = exports.running.value) }
    }

    /** Deletes a backup file; [onFailed] runs when it could not be deleted. */
    fun deleteBackupFile(
        file: File,
        onFailed: () -> Unit,
    ) {
        scope.launch {
            if (data.deleteBackupFile(file)) loadFiles() else onFailed()
        }
    }

    /** Clears local storage, then runs [onCleared] (which opens the empty Library). */
    fun clearLocalStorage(onCleared: () -> Unit) {
        scope.launch {
            withContext(NonCancellable) { data.clearAll() }
            onCleared()
        }
    }

    private suspend fun follow() {
        coroutineScope {
            launch { exports.finished.drop(1).collect { loadFiles() } }
            exports.running.collect { running -> mutableState.update { it.copy(export = running) } }
        }
    }

    private suspend fun loadFiles() {
        val files =
            try {
                BackupFilesState.Loaded(data.backupFiles())
            } catch (error: CancellationException) {
                throw error
            } catch (
                @Suppress("TooGenericExceptionCaught") error: Exception,
            ) {
                Timber.w(error, "Backup files could not be listed")
                BackupFilesState.Failed
            }
        mutableState.update { it.copy(files = files) }
    }
}
