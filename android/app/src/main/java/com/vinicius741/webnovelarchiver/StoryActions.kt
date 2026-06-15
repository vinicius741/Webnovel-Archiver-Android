package com.vinicius741.webnovelarchiver

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.vinicius741.webnovelarchiver.core.BrowserUrlPlanning
import com.vinicius741.webnovelarchiver.core.Chapter
import com.vinicius741.webnovelarchiver.core.EpubConfig
import com.vinicius741.webnovelarchiver.core.EpubSelection
import com.vinicius741.webnovelarchiver.core.FileMimeTypes
import com.vinicius741.webnovelarchiver.core.SourceRegistry
import com.vinicius741.webnovelarchiver.core.SourceUrlValidation
import com.vinicius741.webnovelarchiver.core.Story
import com.vinicius741.webnovelarchiver.core.StoryActionGuards
import com.vinicius741.webnovelarchiver.core.TextCleanup
import com.vinicius741.webnovelarchiver.download.DownloadForegroundService
import com.vinicius741.webnovelarchiver.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal fun ScreenHost.queueDownload(story: Story, indexes: List<Int>) {
    if (!StoryActionGuards.canQueueDownloads(story)) {
        toast(StoryActionGuards.archivedActionMessage("Downloading"))
        return
    }
    downloadEngine.queue(story, indexes, startNow = false)
    DownloadForegroundService.start(app)
}

internal fun ScreenHost.syncStory(url: String, tabId: String?) {
    if (url.isBlank()) return toast("Enter a URL")
    screen(title = "Working", onBack = null) { centerLoading("Starting...") }
    scope.launch {
        try {
            val existingBeforeSync = withContext(Dispatchers.IO) {
                SourceRegistry.getProvider(url)?.let { provider ->
                    runCatching { storage.getStory(provider.getStoryId(url)) }.getOrNull()
                }
            }
            val story = withContext(Dispatchers.IO) { syncEngine.fetchOrSync(url, tabId) { msg -> app.runOnUiThread { screen(title = "Working", onBack = null) { centerLoading(msg) } } } }
            if (existingBeforeSync != null && !story.pendingNewChapterIds.isNullOrEmpty()) {
                val pending = story.pendingNewChapterIds.orEmpty().toSet()
                val indexes = story.chapters.mapIndexedNotNull { index, chapter ->
                    if (chapter.id in pending && !chapter.downloaded) index else null
                }
                if (indexes.isNotEmpty()) {
                    queueDownload(story, indexes)
                }
            }
            showDetails(story.id)
        } catch (error: Throwable) {
            toast(error.message ?: "Sync failed")
            showLibrary()
        }
    }
}

internal fun ScreenHost.syncStory(story: Story) {
    if (!StoryActionGuards.canSync(story)) {
        toast(StoryActionGuards.archivedActionMessage("Sync"))
        return
    }
    syncStory(story.sourceUrl, story.tabId)
}

internal fun ScreenHost.applyCleanup(story: Story) {
    // G5: show a loading state so the screen doesn't appear frozen while cleanup runs.
    screen(title = "Applying Cleanup", onBack = null) { centerLoading("Cleaning chapters...") }
    scope.launch(Dispatchers.IO) {
        story.chapters.filter { it.downloaded }.forEachIndexed { _, chapter ->
            val html = storage.readChapter(chapter) ?: return@forEachIndexed
            File(chapter.filePath!!).writeText(TextCleanup.applyDownloadCleanup(html, storage.getSentenceRemovalList(), storage.getRegexRules()))
        }
        story.epubStale = true
        storage.addOrUpdateStory(story)
        withContext(Dispatchers.Main) { toast("Cleanup applied"); showDetails(story.id) }
    }
}

internal fun ScreenHost.generateConfiguredEpub(story: Story, config: EpubConfig) {
    val selectedEntries = EpubSelection.selectDownloadedChapters(story, config)
    if (selectedEntries.isEmpty()) {
        toast("No downloaded chapters in selected EPUB range")
        return
    }
    generateEpub(
        story,
        selectedEntries.map { it.chapter },
        config.maxChaptersPerEpub,
        selectedEntries.map { it.originalChapterNumber },
    )
}

internal fun ScreenHost.generateEpub(
    story: Story,
    chapters: List<Chapter>,
    maxChaptersPerFile: Int = storage.getSettings().maxChaptersPerEpub,
    originalChapterNumbers: List<Int>? = null,
) {
    scope.launch {
        try {
            screen(title = "Generating EPUB", onBack = null) { centerLoading("Preparing...") }
            val results = epubEngine.generate(story, chapters, maxChaptersPerFile, originalChapterNumbers) { msg -> app.runOnUiThread { screen(title = "Generating EPUB", onBack = null) { centerLoading(msg) } } }
            toast("Generated ${results.size} EPUB file(s)")
            showDetails(story.id)
        } catch (error: Throwable) {
            toast(error.message ?: "EPUB failed")
            showDetails(story.id)
        }
    }
}

internal fun ScreenHost.navigateChapter(story: Story, chapter: Chapter, delta: Int) {
    val next = story.chapters.getOrNull(story.chapters.indexOfFirst { it.id == chapter.id } + delta) ?: return
    showReader(story.id, next.id)
}

/* ---- URL helpers (pure delegates) ---- */

internal fun resolveUrl(input: String): String = BrowserUrlPlanning.resolveUrl(input)

internal fun isNovelUrl(url: String): Boolean = SourceUrlValidation.isImportableStoryUrl(url)

internal fun isGoogleAuthUrl(url: String): Boolean = BrowserUrlPlanning.isGoogleAuthUrl(url)

/* ---- File / share helpers ---- */

internal fun ScreenHost.openFile(path: String?) {
    if (path == null) return toast("No EPUB generated")
    val file = File(path)
    if (!file.exists()) return toast("EPUB file is missing")
    val uri = fileUri(file)
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/epub+zip").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { app.startActivity(intent) }.onFailure { toast("No app available to open EPUB") }
}

internal fun ScreenHost.openEpubForStory(story: Story) {
    val paths = story.epubPaths?.filter { it.isNotBlank() }?.ifEmpty { null }
    val candidates = paths ?: story.epubPath?.let { listOf(it) }.orEmpty()
    if (candidates.isEmpty()) return toast("No EPUB generated")

    val existing = candidates.filter { File(it).exists() }
    if (existing.isEmpty()) {
        story.epubPath = null
        story.epubPaths = null
        story.epubStale = false
        storage.addOrUpdateStory(story)
        toast("EPUB file not found. Please regenerate.")
        showDetails(story.id)
        return
    }

    if (existing.size != candidates.size) {
        story.epubPaths = existing.toMutableList()
        story.epubPath = existing.firstOrNull()
        storage.addOrUpdateStory(story)
        toast("Some EPUB files were missing. ${existing.size} file(s) remain.")
    }

    if (existing.size == 1) {
        openFile(existing.first())
        return
    }

    val labels = existing.map { EpubSelection.displayNameForPath(it) }.toTypedArray()
    AlertDialog.Builder(app)
        .setTitle("Select EPUB to Read")
        .setItems(labels) { _, which -> openFile(existing[which]) }
        .setNegativeButton("Cancel", null)
        .show()
}

internal fun ScreenHost.share(file: File) {
    val intent = Intent(Intent.ACTION_SEND)
        .setType(FileMimeTypes.forFilename(file.name))
        .putExtra(Intent.EXTRA_STREAM, fileUri(file))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    app.startActivity(Intent.createChooser(intent, "Share ${file.name}"))
}

internal fun ScreenHost.exportAndShare(exporter: () -> File) {
    runCatching { share(exporter()) }
        .onFailure { toast(it.message ?: "Export failed") }
}

internal fun ScreenHost.fileUri(file: File): Uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
