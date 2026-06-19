package com.vinicius741.webnovelarchiver

import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.vinicius741.webnovelarchiver.core.BrowserUrlPlanning
import com.vinicius741.webnovelarchiver.core.Chapter
import com.vinicius741.webnovelarchiver.core.CleanupEngine
import com.vinicius741.webnovelarchiver.core.EpubConfig
import com.vinicius741.webnovelarchiver.core.EpubSelection
import com.vinicius741.webnovelarchiver.core.FileMimeTypes
import com.vinicius741.webnovelarchiver.core.SourceRegistry
import com.vinicius741.webnovelarchiver.core.SourceUrlValidation
import com.vinicius741.webnovelarchiver.core.Story
import com.vinicius741.webnovelarchiver.core.StoryActionGuards
import com.vinicius741.webnovelarchiver.download.DownloadForegroundService
import com.vinicius741.webnovelarchiver.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal fun ScreenHost.queueDownload(
    story: Story,
    indexes: List<Int>,
) {
    if (!StoryActionGuards.canQueueDownloads(story)) {
        toast(StoryActionGuards.archivedActionMessage("Downloading"))
        return
    }
    val screenTagAtEnqueue = frame.tag
    val servicePrepared = runCatching { DownloadForegroundService.prepare(app.applicationContext) }
    if (servicePrepared.isFailure) {
        toast(servicePrepared.exceptionOrNull()?.message ?: "Could not start downloads")
        return
    }
    // Queue planning and durable JSON writes scale with the number of chapters, so keep them off
    // the main thread. Process scope ensures persistence and service handoff survive Activity
    // recreation after the user initiated the operation.
    app.appContainer.applicationScope.launch {
        val result =
            runCatching {
                downloadEngine.queue(story, indexes, startNow = false)
                DownloadForegroundService.startPrepared(app.applicationContext)
            }
        if (result.isFailure) {
            runCatching { DownloadForegroundService.abortPrepare(app.applicationContext) }
        }
        app.runOnUiThread {
            if (app.isFinishing || app.isDestroyed) return@runOnUiThread
            result
                .onSuccess {
                    val detailsScreenKey = "${story.title}|by ${story.author}"
                    if (
                        activeStory?.id == story.id &&
                        (frame.tag == screenTagAtEnqueue || frame.tag == detailsScreenKey)
                    ) {
                        showDetails(story.id)
                    }
                }.onFailure { error ->
                    toast(error.message ?: "Could not queue downloads")
                }
        }
    }
}

internal fun ScreenHost.syncStory(
    url: String,
    tabId: String?,
) {
    if (url.isBlank()) return toast("Enter a URL")
    screen(title = "Working", onBack = null) { centerLoading("Starting...") }
    scope.launch {
        try {
            val existingBeforeSync =
                withContext(Dispatchers.IO) {
                    SourceRegistry.getProvider(url)?.let { provider ->
                        runCatching { storage.getStory(provider.getStoryId(url)) }.getOrNull()
                    }
                }
            val story =
                withContext(Dispatchers.IO) {
                    syncEngine.fetchOrSync(
                        url,
                        tabId,
                    ) { msg -> app.runOnUiThread { screen(title = "Working", onBack = null) { centerLoading(msg) } } }
                }
            if (existingBeforeSync != null && !story.pendingNewChapterIds.isNullOrEmpty()) {
                val pending = story.pendingNewChapterIds.orEmpty().toSet()
                val indexes =
                    story.chapters.mapIndexedNotNull { index, chapter ->
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

/**
 * Sync an *existing* story in place — mirrors the RN `StoryActions` pattern: instead of navigating
 * away to a full-screen "Working" page, it drives the Details screen's inline operation UI (the same
 * `StoryOperationState` mechanism EPUB/CLEANUP use) so the button flips to "Syncing..." and a spinner
 * + status line appears right where the user tapped. Status callbacks from [StorySyncEngine.fetchOrSync]
 * ("Fetching from…", "Parsing chapters…") update that line live without leaving the screen.
 *
 * This intentionally does **not** reuse [syncStory]`s `(url, tabId)` overload, whose first line
 * navigates to a `screen(title = "Working")` — that full-screen flow is reserved for brand-new
 * fetches (Library "Fetch Story", Browser "Add") where no Details screen exists yet.
 */
internal fun ScreenHost.syncStory(story: Story) {
    if (!StoryActionGuards.canSync(story)) {
        toast(StoryActionGuards.archivedActionMessage("Sync"))
        return
    }
    // Make sure we're on the Details screen so the inline spinner is visible — Sync can also be
    // triggered from the Library's story-action dialog, where activeStory may differ or be null.
    if (activeStory?.id != story.id) showDetails(story.id)
    setStoryOperation(story.id, StoryOperationKind.SYNC, "Starting...")
    scope.launch {
        try {
            // Re-read the pre-sync state so the new-chapter auto-download logic below matches the
            // url-overload path exactly (it keys off pendingNewChapterIds set during the merge).
            val existingBeforeSync =
                withContext(Dispatchers.IO) {
                    SourceRegistry.getProvider(story.sourceUrl)?.let { provider ->
                        runCatching { storage.getStory(provider.getStoryId(story.sourceUrl)) }.getOrNull()
                    }
                }
            val synced =
                withContext(Dispatchers.IO) {
                    syncEngine.fetchOrSync(story.sourceUrl, story.tabId) { msg ->
                        app.runOnUiThread { setStoryOperation(story.id, StoryOperationKind.SYNC, msg) }
                    }
                }
            if (existingBeforeSync != null && !synced.pendingNewChapterIds.isNullOrEmpty()) {
                val pending = synced.pendingNewChapterIds.orEmpty().toSet()
                val indexes =
                    synced.chapters.mapIndexedNotNull { index, chapter ->
                        if (chapter.id in pending && !chapter.downloaded) index else null
                    }
                if (indexes.isNotEmpty()) {
                    queueDownload(synced, indexes)
                }
            }
            clearStoryOperation(synced.id, StoryOperationKind.SYNC, rerender = false)
            showDetails(synced.id)
        } catch (error: Throwable) {
            clearStoryOperation(story.id, StoryOperationKind.SYNC, rerender = false)
            toast(error.message ?: "Sync failed")
            // Stay on Details (not the Library) so the user sees the result in context.
            showDetails(story.id)
        }
    }
}

internal fun ScreenHost.applyCleanup(story: Story) {
    if (storyOperation != null) {
        toast("Please wait for the current operation to finish")
        return
    }
    val downloaded = story.chapters.filter { it.downloaded }
    if (downloaded.isEmpty()) {
        toast("No downloaded chapters to process")
        return
    }
    confirm(
        "This will apply sentence removal and regex cleanup rules to ${downloaded.size} downloaded chapters. " +
            "The EPUB will need to be regenerated afterward.",
        confirmLabel = "Apply",
    ) {
        runCleanup(story, downloaded)
    }
}

private fun ScreenHost.runCleanup(
    story: Story,
    downloaded: List<Chapter>,
) {
    setStoryOperation(story.id, StoryOperationKind.CLEANUP, "Processing...")
    scope.launch(Dispatchers.IO) {
        try {
            val sentenceRemoval = storage.getSentenceRemovalList()
            val regexRules = storage.getRegexRules()
            var processed = 0
            var errors = 0
            var sentencesRemoved = 0
            downloaded.forEachIndexed { index, chapter ->
                withContext(Dispatchers.Main) {
                    setStoryOperation(
                        story.id,
                        StoryOperationKind.CLEANUP,
                        "Processing ${index + 1}/${downloaded.size}: ${chapter.title}",
                        (index + 1).toFloat() / downloaded.size,
                    )
                }
                runCatching {
                    val html = storage.readChapter(chapter) ?: error("Downloaded chapter file is missing")
                    val result = CleanupEngine.shared.applyDownloadWithStats(html, sentenceRemoval, regexRules)
                    check(storage.overwriteChapter(chapter, result.html)) { "Downloaded chapter file is missing" }
                    result
                }.onSuccess { result ->
                    processed += 1
                    sentencesRemoved += result.sentencesRemoved
                }.onFailure {
                    errors += 1
                }
            }
            if (processed > 0) {
                story.epubStale = true
                storage.addOrUpdateStory(story)
            }
            withContext(Dispatchers.Main) {
                clearStoryOperation(story.id, StoryOperationKind.CLEANUP, rerender = false)
                showDetails(story.id)
                val sentenceLine =
                    "$sentencesRemoved sentence${if (sentencesRemoved == 1) "" else "s"} removed."
                if (errors > 0) {
                    alert(
                        "Processing Complete with Errors",
                        "Processed $processed chapters; $errors had errors. $sentenceLine",
                    )
                } else {
                    alert(
                        "Processing Complete",
                        "Successfully applied text cleanup to $processed chapters. $sentenceLine " +
                            "Please regenerate the EPUB.",
                    )
                }
            }
        } catch (error: Throwable) {
            withContext(Dispatchers.Main) {
                clearStoryOperation(story.id, StoryOperationKind.CLEANUP, rerender = false)
                toast(error.message ?: "Cleanup failed")
                showDetails(story.id)
            }
        }
    }
}

internal fun ScreenHost.generateConfiguredEpub(
    story: Story,
    config: EpubConfig,
) {
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
    if (storyOperation != null) {
        toast("Please wait for the current operation to finish")
        return
    }
    scope.launch {
        try {
            setStoryOperation(story.id, StoryOperationKind.EPUB, "Preparing...")
            val results =
                epubEngine.generate(story, chapters, maxChaptersPerFile, originalChapterNumbers) { msg ->
                    app.runOnUiThread {
                        setStoryOperation(story.id, StoryOperationKind.EPUB, msg, epubProgressFromMessage(msg))
                    }
                }
            clearStoryOperation(story.id, StoryOperationKind.EPUB, rerender = false)
            toast("Generated ${results.size} EPUB file(s)")
            showDetails(story.id)
        } catch (error: Throwable) {
            clearStoryOperation(story.id, StoryOperationKind.EPUB, rerender = false)
            toast(error.message ?: "EPUB failed")
            showDetails(story.id)
        }
    }
}

internal fun ScreenHost.navigateChapter(
    story: Story,
    chapter: Chapter,
    delta: Int,
) {
    val next = story.chapters.getOrNull(story.chapters.indexOfFirst { it.id == chapter.id } + delta) ?: return
    showReader(story.id, next.id)
}

// ---- URL helpers (pure delegates) ----

internal fun resolveUrl(input: String): String = BrowserUrlPlanning.resolveUrl(input)

internal fun isNovelUrl(url: String): Boolean = SourceUrlValidation.isImportableStoryUrl(url)

// ---- File / share helpers ----

internal fun ScreenHost.openFile(path: String?) {
    if (path == null) return toast("No EPUB generated")
    val file = storage.resolveAbsolutePath(path) ?: return toast("EPUB file is missing")
    val uri = fileUri(file)
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/epub+zip").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { app.startActivity(intent) }.onFailure { toast("No app available to open EPUB") }
}

internal fun ScreenHost.openEpubForStory(story: Story) {
    val paths = story.epubPaths?.filter { it.isNotBlank() }?.ifEmpty { null }
    val candidates = paths ?: story.epubPath?.let { listOf(it) }.orEmpty()
    if (candidates.isEmpty()) return toast("No EPUB generated")

    val existing =
        candidates.mapNotNull { candidate ->
            storage.resolveAbsolutePath(candidate)?.let { candidate to it }
        }
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
        val remainingPaths = existing.map { it.first }
        story.epubPaths = remainingPaths.toMutableList()
        story.epubPath = remainingPaths.firstOrNull()
        storage.addOrUpdateStory(story)
        toast("Some EPUB files were missing. ${existing.size} file(s) remain.")
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

internal fun ScreenHost.exportAndShare(exporter: () -> File) {
    runCatching { share(exporter()) }
        .onFailure { toast(it.message ?: "Export failed") }
}

internal fun ScreenHost.fileUri(file: File): Uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)

private fun ScreenHost.setStoryOperation(
    storyId: String,
    kind: StoryOperationKind,
    message: String,
    progress: Float? = null,
) {
    storyOperation = StoryOperationState(storyId, kind, message, progress)
    if (activeStory?.id == storyId) showDetails(storyId)
}

private fun ScreenHost.clearStoryOperation(
    storyId: String,
    kind: StoryOperationKind,
    rerender: Boolean = true,
) {
    if (storyOperation?.storyId == storyId && storyOperation?.kind == kind) {
        storyOperation = null
        if (rerender && activeStory?.id == storyId) showDetails(storyId)
    }
}

private fun epubProgressFromMessage(message: String): Float? {
    val match = Regex("""Generating EPUB\s+(\d+)/(\d+)""").find(message) ?: return null
    val current = match.groupValues[1].toFloatOrNull() ?: return null
    val total = match.groupValues[2].toFloatOrNull()?.takeIf { it > 0f } ?: return null
    return (current / total).coerceIn(0f, 1f)
}
