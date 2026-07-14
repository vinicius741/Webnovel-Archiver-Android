package com.vinicius741.webnovelarchiver.feature.story

import com.vinicius741.webnovelarchiver.cleanup.CleanupEngine
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.EpubConfig
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.epub.EpubSelection
import com.vinicius741.webnovelarchiver.feature.details.renderStoryOperationProgress
import com.vinicius741.webnovelarchiver.feature.details.showDetails
import com.vinicius741.webnovelarchiver.feature.reader.showReader
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.navigation.StoryOperationKind
import com.vinicius741.webnovelarchiver.navigation.StoryOperationState
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
            val sentenceRemoval = repository.getSentenceRemovalList()
            val regexRules = repository.getRegexRules()
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
                    val html = repository.readChapter(chapter) ?: error("Downloaded chapter file is missing")
                    val result = CleanupEngine.shared.applyDownloadWithStats(html, sentenceRemoval, regexRules)
                    check(repository.overwriteChapter(chapter, result.html)) { "Downloaded chapter file is missing" }
                    result
                }.onSuccess { result ->
                    processed += 1
                    sentencesRemoved += result.sentencesRemoved
                }.onFailure {
                    errors += 1
                }
            }
            if (processed > 0) {
                check(repository.markCleanupApplied(story.id) != null) {
                    "Story was removed while cleanup was running"
                }
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
            // E1: structured-concurrency safety — never swallow cancellation.
            if (error is CancellationException) throw error
            Timber.w(error, "Cleanup failed for %s", story.id)
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
    val run = {
        generateEpub(
            story,
            selectedEntries.map { it.chapter },
            config.maxChaptersPerEpub,
            selectedEntries.map { it.originalChapterNumber },
        )
    }
    // Warn before producing a partial EPUB: non-downloaded chapters in range are silently skipped by
    // EpubSelection, so surface the gap and let the user confirm rather than generating unawares.
    val coverage = EpubSelection.rangeCoverage(story, config)
    if (coverage.missing > 0) {
        showConfirmEpubWithMissingChaptersDialog(coverage) { run() }
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
    maxChaptersPerFile: Int = repository.getSettings().maxChaptersPerEpub,
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
            // E1: structured-concurrency safety — never swallow cancellation.
            if (error is CancellationException) throw error
            Timber.w(error, "EPUB generation failed for %s", story.id)
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
        showDetails(storyId)
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
        if (rerender && activeStory?.id == storyId) showDetails(storyId)
    }
}

private fun epubProgressFromMessage(message: String): Float? {
    val match = Regex("""Generating EPUB\s+(\d+)/(\d+)""").find(message) ?: return null
    val current = match.groupValues[1].toFloatOrNull() ?: return null
    val total = match.groupValues[2].toFloatOrNull()?.takeIf { it > 0f } ?: return null
    return (current / total).coerceIn(0f, 1f)
}
