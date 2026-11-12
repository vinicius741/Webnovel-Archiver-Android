package com.vinicius741.webnovelarchiver.feature.story

import com.vinicius741.webnovelarchiver.cleanup.CleanupEngine
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.EpubConfig
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.epub.EpubConfigPlanning
import com.vinicius741.webnovelarchiver.epub.EpubSelection
import com.vinicius741.webnovelarchiver.feature.details.refreshStoryHero
import com.vinicius741.webnovelarchiver.feature.details.renderStoryOperationProgress
import com.vinicius741.webnovelarchiver.feature.details.showStoryScreen
import com.vinicius741.webnovelarchiver.feature.reader.showReader
import com.vinicius741.webnovelarchiver.feature.updates.isSyncingFromUpdates
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.navigation.StoryOperationKind
import com.vinicius741.webnovelarchiver.navigation.StoryOperationState
import com.vinicius741.webnovelarchiver.source.SourceRegistry
import com.vinicius741.webnovelarchiver.ui.alert
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.size
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

@Suppress(
    "TooGenericExceptionCaught",
    "InstanceOfCheckForException",
)
// E1: route non-cancellation failures through the user-facing error path.
private fun ScreenHost.runCleanup(
    story: Story,
    downloaded: List<Chapter>,
) {
    setStoryOperation(story.id, StoryOperationKind.CLEANUP, "Processing...")
    scope.launch(Dispatchers.IO) {
        try {
            val sentenceRemoval = repository.sentenceRemovalList.get()
            val regexRules = repository.regexRules.get()
            val noticeMarker = SourceRegistry.getProvider(story.sourceId, story.sourceUrl)?.insertedNoticeMarker
            var processed = 0
            var errors = 0
            var changed = 0
            var sentencesRemoved = 0
            var regexMatchesRemoved = 0
            val startedAt = System.nanoTime()
            downloaded.forEachIndexed { index, chapter ->
                withContext(Dispatchers.Main) {
                    setStoryOperation(
                        story.id,
                        StoryOperationKind.CLEANUP,
                        "Processing ${index + 1}/${downloaded.size}: ${chapter.title}",
                        (index + 1).toFloat() / downloaded.size,
                    )
                }
                try {
                    val html = repository.readChapter(chapter) ?: error("Downloaded chapter file is missing")
                    val result = CleanupEngine.shared.applyDownloadWithStats(html, sentenceRemoval, regexRules, noticeMarker)
                    // Skip writes whose cleaned HTML equals the original.
                    if (result.html != html) {
                        // Persist the stale marker before the first chapter modification:
                        // cancellation, process death, or a later failure must never leave modified
                        // chapter files under an EPUB still marked current. A run that changes
                        // nothing leaves the EPUB's freshness untouched.
                        if (changed == 0) markCleanupAppliedOrAbort(story.id)
                        check(repository.overwriteChapter(chapter, result.html)) { "Downloaded chapter file is missing" }
                        changed += 1
                    }
                    processed += 1
                    sentencesRemoved += result.sentencesRemoved
                    regexMatchesRemoved += result.regexMatchesRemoved
                } catch (error: Exception) {
                    // The per-chapter boundary must rethrow cancellation, not count it as an error, and
                    // a deleted story aborts the whole run rather than failing chapter by chapter.
                    if (error is CancellationException || error is CleanupStoryRemovedException) throw error
                    errors += 1
                }
            }
            Timber.i(
                "Text cleanup of %d chapters (%d changed) took %d ms",
                downloaded.size,
                changed,
                (System.nanoTime() - startedAt) / NANOS_PER_MILLI,
            )
            withContext(Dispatchers.Main) {
                clearStoryOperation(story.id, StoryOperationKind.CLEANUP, rerender = false)
                showStoryScreen(story.id)
                val summary =
                    CleanupSummaryPlanning.summarize(
                        CleanupRunStats(
                            totalChapters = downloaded.size,
                            processedChapters = processed,
                            failedChapters = errors,
                            changedChapters = changed,
                            sentencesRemoved = sentencesRemoved,
                            regexMatchesRemoved = regexMatchesRemoved,
                        ),
                    )
                alert(summary.title, summary.message)
            }
        } catch (error: Throwable) {
            // E1: structured-concurrency safety — never swallow cancellation.
            if (error is CancellationException) throw error
            Timber.w(error, "Cleanup failed for %s", story.id)
            withContext(Dispatchers.Main) {
                clearStoryOperation(story.id, StoryOperationKind.CLEANUP, rerender = false)
                toast(error.message ?: "Cleanup failed")
                showStoryScreen(story.id)
            }
        }
    }
}

private const val NANOS_PER_MILLI = 1_000_000L

private class CleanupStoryRemovedException : IllegalStateException("Story was removed while cleanup was running")

private suspend fun ScreenHost.markCleanupAppliedOrAbort(storyId: String) {
    if (repository.markCleanupApplied(storyId) == null) throw CleanupStoryRemovedException()
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
    scope.launch {
        try {
            setStoryOperation(story.id, StoryOperationKind.EPUB, "Preparing...")
            val results =
                epubEngine.generate(story, chapters, config, originalChapterNumbers) { msg ->
                    app.runOnUiThread {
                        setStoryOperation(story.id, StoryOperationKind.EPUB, msg, epubProgressFromMessage(msg))
                    }
                }
            clearStoryOperation(story.id, StoryOperationKind.EPUB, rerender = false)
            toast("Generated ${results.size} EPUB file(s)")
            showStoryScreen(story.id)
        } catch (error: Throwable) {
            // E1: structured-concurrency safety — never swallow cancellation.
            if (error is CancellationException) throw error
            Timber.w(error, "EPUB generation failed for %s", story.id)
            clearStoryOperation(story.id, StoryOperationKind.EPUB, rerender = false)
            toast(error.message ?: "EPUB failed")
            showStoryScreen(story.id)
        }
    }
}

internal fun ScreenHost.navigateChapter(
    story: Story,
    chapter: Chapter,
    delta: Int,
) {
    // Walk from the current chapter toward delta and open the first downloaded chapter, skipping
    // gaps. Non-downloaded chapters aren't readable (no reader placeholder), so prev/next should land
    // on real content rather than a dead end. If there is no downloaded chapter in that direction,
    // do nothing — matching the previous `?: return` behavior at the end of the list.
    val startIndex = story.chapters.indexOfFirst { it.id == chapter.id }
    if (startIndex < 0) return
    var i = startIndex + delta
    while (i in story.chapters.indices) {
        if (story.chapters[i].downloaded) {
            showReader(story.id, story.chapters[i].id)
            return
        }
        i += delta
    }
}

internal fun ScreenHost.setStoryOperation(
    storyId: String,
    kind: StoryOperationKind,
    message: String,
    progress: Float? = null,
) {
    val previous = storyOperation
    val next = StoryOperationState(storyId, kind, message, progress)
    storyOperation = next
    if (activeStory?.id != storyId) return
    // First tick for this operation (or kind change): full Details rebuild so buttons disable and
    // the progress slot is allocated. Subsequent ticks only swap the slot's message/bar — a full
    // rebuild per chapter was the cleanup flicker (blank frame + scroll restore on every update).
    val canPatchInPlace =
        previous?.storyId == storyId &&
            previous.kind == kind &&
            detailsOperationSlot != null
    if (canPatchInPlace) {
        renderStoryOperationProgress(detailsOperationSlot!!, next)
    } else {
        refreshStoryHero(storyId)
    }
}

internal fun ScreenHost.clearStoryOperation(
    storyId: String,
    kind: StoryOperationKind,
    rerender: Boolean = true,
) {
    if (storyOperation?.storyId == storyId && storyOperation?.kind == kind) {
        storyOperation = null
        detailsOperationSlot = null
        if (rerender && activeStory?.id == storyId) refreshStoryHero(storyId)
    }
}

private fun epubProgressFromMessage(message: String): Float? {
    val match = Regex("""Generating EPUB\s+(\d+)/(\d+)""").find(message) ?: return null
    val current = match.groupValues[1].toFloatOrNull() ?: return null
    val total = match.groupValues[2].toFloatOrNull()?.takeIf { it > 0f } ?: return null
    return (current / total).coerceIn(0f, 1f)
}
