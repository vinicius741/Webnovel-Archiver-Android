package com.vinicius741.webnovelarchiver.feature.story

import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.vinicius741.webnovelarchiver.data.backup.FileMimeTypes
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.epub.EpubSelection
import com.vinicius741.webnovelarchiver.feature.browser.BrowserUrlPlanning
import com.vinicius741.webnovelarchiver.feature.details.showDetails
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.source.SourceUrlValidation
import com.vinicius741.webnovelarchiver.ui.showStyledOptionsDialog
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.launch
import java.io.File

internal fun resolveUrl(input: String): String = BrowserUrlPlanning.resolveUrl(input)

internal fun isNovelUrl(url: String): Boolean = SourceUrlValidation.isImportableStoryUrl(url)

internal fun ScreenHost.openFile(path: String?) {
    if (path == null) return toast("No EPUB generated")
    val file = repository.resolveAbsolutePath(path) ?: return toast("EPUB file is missing")
    val uri = fileUri(file)
    val intent =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/epub+zip")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { app.startActivity(intent) }.onFailure { toast("No app available to open EPUB") }
}

internal fun ScreenHost.openEpubForStory(story: Story) {
    val paths = story.epubPaths?.filter { it.isNotBlank() }?.ifEmpty { null }
    val candidates = paths ?: story.epubPath?.let { listOf(it) }.orEmpty()
    if (candidates.isEmpty()) return toast("No EPUB generated")

    val existing =
        candidates.mapNotNull { candidate ->
            repository.resolveAbsolutePath(candidate)?.let { candidate to it }
        }
    if (existing.isEmpty()) {
        scope.launch {
            repository.retainEpubPaths(story.id, emptyList())
            toast("EPUB file not found. Please regenerate.")
            showDetails(story.id)
        }
        return
    }

    if (existing.size != candidates.size) {
        val remainingPaths = existing.map { it.first }
        scope.launch {
            repository.retainEpubPaths(story.id, remainingPaths)
            toast("Some EPUB files were missing. ${existing.size} file(s) remain.")
        }
    }

    if (existing.size == 1) {
        openFile(existing.first().first)
        return
    }

    val options =
        existing.map { (path, file) ->
            EpubSelection.displayNameForPath(file.absolutePath) to { openFile(path) }
        }
    showStyledOptionsDialog("Select EPUB to Read", options)
}

internal fun ScreenHost.share(file: File) {
    val intent =
        Intent(Intent.ACTION_SEND)
            .setType(FileMimeTypes.forFilename(file.name))
            .putExtra(Intent.EXTRA_STREAM, fileUri(file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    app.startActivity(Intent.createChooser(intent, "Share ${file.name}"))
}

internal fun ScreenHost.exportAndShare(
    exporter: suspend () -> File,
    onFinally: (() -> Unit)? = null,
) {
    scope.launch {
        try {
            runCatching { share(exporter()) }
                .onFailure { toast(it.message ?: "Export failed") }
        } finally {
            onFinally?.invoke()
        }
    }
}

internal fun ScreenHost.fileUri(file: File): Uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
