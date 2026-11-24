package com.vinicius741.webnovelarchiver.feature.library

import com.vinicius741.webnovelarchiver.navigation.ScreenModel
import com.vinicius741.webnovelarchiver.source.local.LocalEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What a scan of the local folder found. [folderName] is null when no folder is chosen (or access was lost). */
internal data class LocalScan(
    val folderName: String?,
    val entries: List<LocalEntry>,
)

internal data class LocalEntryRow(
    val entry: LocalEntry,
    val inLibrary: Boolean,
)

internal data class LocalLibraryUiState(
    /** False until the first scan finishes. */
    val scanned: Boolean,
    val folderName: String?,
    val rows: List<LocalEntryRow>,
    val scanning: Boolean,
    val error: String?,
    /** The novel being imported and its latest status. */
    val importingUrl: String?,
    val importStatus: String?,
    /** Novels still waiting in an "Add all" run, after the current one. */
    val queued: List<String>,
    /** "Name: reason" for each novel that could not be added since the last scan or Add all. */
    val failures: List<String> = emptyList(),
) {
    val folderChosen: Boolean get() = folderName != null
    val importing: Boolean get() = importingUrl != null
    val notInLibrary: List<LocalEntryRow> get() = rows.filterNot { it.inLibrary }
}

/**
 * The Local folder screen (`AppRoute.LocalLibrary`): the chosen folder, the novels in it and which
 * are already in the library, and the import in progress. Imports run through the shared story
 * fetch (the renderer starts it, as Add story does) and report back here; "Add all" imports the
 * missing novels one at a time.
 */
internal class LocalLibraryScreenModel(
    private val scan: () -> LocalScan,
    private val libraryIds: () -> Set<String>,
    private val chooseFolder: (String) -> Unit,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val io: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
) : ScreenModel(scope) {
    private var scanResult: LocalScan? = null
    private var scanning = false
    private var error: String? = null
    private var importingUrl: String? = null
    private var importStatus: String? = null
    private val queued = ArrayDeque<String>()
    private val failures = mutableListOf<String>()
    private var scanJob: Job? = null
    private var cleared = false
    private val mutableState = MutableStateFlow(compute())

    val state: StateFlow<LocalLibraryUiState> = mutableState.asStateFlow()

    /** Recomputes the in-library marks (the library may have changed) and returns the state. */
    fun refresh(): LocalLibraryUiState = publish()

    /** Scans the folder again. A running scan is left to finish unless [restart] (a new folder) replaces it. */
    fun rescan(restart: Boolean = false) {
        if (scanning && !restart) return
        scanJob?.cancel()
        scanning = true
        error = null
        if (importingUrl == null) failures.clear()
        publish()
        scanJob =
            scope.launch {
                val result =
                    try {
                        Result.success(withContext(io) { scan() })
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (
                        @Suppress("TooGenericExceptionCaught") failure: Exception,
                    ) {
                        Result.failure(failure)
                    }
                scanning = false
                result
                    .onSuccess { scanResult = it }
                    .onFailure {
                        // A folder the app cannot read any more (moved, deleted, storage removed) shows
                        // the folder picker with the reason, not an endless "Reading your folder".
                        scanResult = LocalScan(folderName = null, entries = emptyList())
                        error = it.message ?: "Could not read the local folder"
                    }
                publish()
            }
    }

    /** The user picked [treeUri] as the local folder. */
    fun folderPicked(treeUri: String) {
        scope.launch {
            val failure = runCatching { withContext(io) { chooseFolder(treeUri) } }.exceptionOrNull()
            if (failure is CancellationException) throw failure
            if (failure != null) {
                error = failure.message ?: "Could not open that folder"
                publish()
            } else {
                rescan(restart = true)
            }
        }
    }

    /** Starts importing [storyUrl]; false while another import runs. */
    fun startImport(storyUrl: String): Boolean {
        if (importingUrl != null) return false
        importingUrl = storyUrl
        importStatus = "Starting..."
        publish()
        return true
    }

    /** Queues every novel not yet in the library; returns the first to import, or null when none is left. */
    fun startImportAll(): String? {
        if (importingUrl != null) return null
        queued.clear()
        failures.clear()
        queued.addAll(state.value.notInLibrary.map { it.entry.storyUrl })
        val first = queued.removeFirstOrNull() ?: return null
        return first.takeIf { startImport(it) }
    }

    fun importStatus(message: String) {
        if (importingUrl == null) return
        importStatus = message
        publish()
    }

    /**
     * The import ended; returns the next queued novel to import, if any. An "Add all" run ends with
     * the screen: once its route left the history, nothing more is imported.
     */
    fun importFinished(): String? {
        importingUrl = null
        importStatus = null
        val next = queued.removeFirstOrNull()?.takeIf { !cleared }
        if (next == null) publish() else startImport(next)
        return next
    }

    /** The import failed: the reason is listed and an "Add all" run goes on with the next novel. */
    fun importFailed(reason: String): String? {
        val name =
            state.value.rows
                .firstOrNull { it.entry.storyUrl == importingUrl }
                ?.entry
                ?.name
        failures += listOfNotNull(name, reason).joinToString(": ")
        return importFinished()
    }

    override fun onCleared() {
        cleared = true
        queued.clear()
    }

    private fun publish(): LocalLibraryUiState = compute().also { mutableState.value = it }

    private fun compute(): LocalLibraryUiState {
        val result = scanResult
        val ids = libraryIds()
        return LocalLibraryUiState(
            scanned = result != null,
            folderName = result?.folderName,
            rows = result?.entries.orEmpty().map { LocalEntryRow(it, it.storyId in ids) },
            scanning = scanning,
            error = error,
            importingUrl = importingUrl,
            importStatus = importStatus,
            queued = queued.toList(),
            failures = failures.toList(),
        )
    }
}
