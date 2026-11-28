package com.vinicius741.webnovelarchiver.feature.settings

import com.vinicius741.webnovelarchiver.app.appContainer
import com.vinicius741.webnovelarchiver.data.diagnostics.ActivityLogExporter
import com.vinicius741.webnovelarchiver.data.diagnostics.BypassLogExporter
import com.vinicius741.webnovelarchiver.data.diagnostics.DiagnosticsDownloads
import com.vinicius741.webnovelarchiver.feature.story.shareAll
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.launch

/**
 * Exports the activity log and the source-access log together, saves a copy of both to
 * Downloads/Webnovel Archiver (so an app that rejects shared files can still attach them from its
 * own file picker), and shares both in one chooser: one action for "send this to debug a problem",
 * wherever it is offered.
 */
internal fun ScreenHost.shareDiagnostics() {
    val container = app.appContainer
    val directory = container.repository.storage.backupRoot
    scope.launch {
        runCatching {
            val queue = repository.queue()
            listOf(
                ActivityLogExporter.export(
                    directory = directory,
                    library = repository.readOnlyLibrary(),
                    queue = queue,
                    reliability = container.network.reliabilitySnapshots(),
                ),
                BypassLogExporter.export(directory, container.network, queue),
            )
        }.onSuccess { files ->
            if (DiagnosticsDownloads.isSupported) {
                runCatching { DiagnosticsDownloads.save(app, files) }
                    .onSuccess { toast("Saved to ${DiagnosticsDownloads.folderLabel}") }
                    .onFailure { toast("Could not save to Downloads: ${it.message ?: "unknown error"}") }
            }
            shareAll(files, "Share diagnostics")
        }.onFailure { toast(it.message ?: "Export failed") }
    }
}
