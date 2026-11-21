package com.vinicius741.webnovelarchiver.feature.story

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.EpubConfig
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.epub.EpubConfigPlanning
import com.vinicius741.webnovelarchiver.epub.EpubSelection
import com.vinicius741.webnovelarchiver.feature.details.showDetails
import com.vinicius741.webnovelarchiver.feature.details.storyTabShowing
import com.vinicius741.webnovelarchiver.feature.updates.isSyncingFromUpdates
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.navigation.storyOperation
import com.vinicius741.webnovelarchiver.operations.StoryWork
import com.vinicius741.webnovelarchiver.ui.alert
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import timber.log.Timber

internal fun ScreenHost.applyCleanup(story: Story) {
    if (storyOperation != null || isSyncingFromUpdates(story.id)) {
        toast("Please wait for the current operation to finish")
        return
    }
    val downloaded = story.chapters.filter { it.downloaded }
    if (downloaded.isEmpty()) {
        toast("No downloaded chapters to process")
        return
    }
    confirm(
        "This will apply your sentence removal list and regex cleanup rules to ${downloaded.size} downloaded chapters. " +
            "If any text is removed, you'll need to regenerate the EPUB afterward.",
        confirmLabel = "Apply",
    ) {
        runCleanup(story, downloaded)
    }
}

@Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException")
// E1: route non-cancellation failures through the user-facing error path.
private fun ScreenHost.runCleanup(
    story: Story,
    downloaded: List<Chapter>,
) {
    // Undispatched, so the operation slot is taken before this returns and a second tap is refused.
    scope.launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            val stats = storyWork.cleanup(story, downloaded)
            showStoryPage(story.id)
            val summary = CleanupSummaryPlanning.summarize(stats)
            alert(summary.title, summary.message)
        } catch (error: Throwable) {
            // E1: structured-concurrency safety — never swallow cancellation.
            if (error is CancellationException) throw error
            Timber.w(error, "Cleanup failed for %s", story.id)
            toast(error.message ?: "Cleanup failed")
            showStoryPage(story.id)
        }
    }
}

internal fun ScreenHost.generateConfiguredEpub(story: Story) {
    val latest = repository.story(story.id) ?: return
    val currentConfig = EpubConfigPlanning.resolve(latest, repository.settings.get().maxChaptersPerEpub)
    val selectedEntries = EpubSelection.selectDownloadedChapters(latest, currentConfig)
    if (selectedEntries.isEmpty()) {
        toast("No downloaded chapters in selected EPUB range")
        return
    }
    val run = run@{
        // Downloads can complete while the confirmation dialog is open. Include those chapters too.
        val current = repository.story(story.id) ?: return@run
        val configAtGeneration = EpubConfigPlanning.resolve(current, repository.settings.get().maxChaptersPerEpub)
        val entries = EpubSelection.selectDownloadedChapters(current, configAtGeneration)
        if (entries.isEmpty()) {
            toast("No downloaded chapters in selected EPUB range")
            return@run
        }
        generateEpub(
            current,
            entries.map { it.chapter },
            configAtGeneration,
            entries.map { it.originalChapterNumber },
        )
    }
    // Warn before producing a partial EPUB: non-downloaded chapters in range are silently skipped by
    // EpubSelection, so surface the gap and let the user confirm rather than generating unawares.
    val coverage = EpubSelection.rangeCoverage(latest, currentConfig)
    if (coverage.missing > 0) {
        showConfirmEpubWithMissingChaptersDialog(story.id, coverage) { run() }
    } else {
        run()
    }
}

@Suppress(
    "TooGenericExceptionCaught",
    "InstanceOfCheckForException",
)
// E1: route non-cancellation failures through the user-facing error path.
internal fun ScreenHost.generateEpub(
    story: Story,
    chapters: List<Chapter>,
    config: EpubConfig = EpubConfigPlanning.resolve(story, repository.settings.get().maxChaptersPerEpub),
    originalChapterNumbers: List<Int>? = null,
) {
    if (storyOperation != null || isSyncingFromUpdates(story.id)) {
        toast("Please wait for the current operation to finish")
        return
    }
    scope.launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            val files = storyWork.epub(story, chapters, config, originalChapterNumbers)
            toast("Generated $files EPUB file(s)")
            showStoryPage(story.id)
        } catch (error: Throwable) {
            // E1: structured-concurrency safety — never swallow cancellation.
            if (error is CancellationException) throw error
            Timber.w(error, "EPUB generation failed for %s", story.id)
            toast(error.message ?: "EPUB failed")
            showStoryPage(story.id)
        }
    }
}

/** The story work (sync, cleanup, EPUB), which publishes its progress to [ScreenHost.operations]. */
internal val ScreenHost.storyWork: StoryWork
    get() = StoryWork.create(operations, repository, syncEngine, epubEngine)

/**
 * Brings the user back to the story after its operation ended. A story tab already on screen
 * redraws itself from the cleared operation, so only another screen navigates to the Overview.
 */
internal fun ScreenHost.showStoryPage(storyId: String) {
    if (!storyTabShowing(storyId)) showDetails(storyId)
}
