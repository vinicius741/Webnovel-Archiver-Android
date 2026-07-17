package com.vinicius741.webnovelarchiver.feature.details

import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.story.StoryActionGuards
import com.vinicius741.webnovelarchiver.feature.library.showLibrary
import com.vinicius741.webnovelarchiver.feature.story.applyCleanup
import com.vinicius741.webnovelarchiver.feature.story.showEpubConfigDialog
import com.vinicius741.webnovelarchiver.feature.story.syncStory
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.source.SourceRegistry
import com.vinicius741.webnovelarchiver.sync.StorySyncMode
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.makeChip
import com.vinicius741.webnovelarchiver.ui.showStyledOptionsDialog
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.launch

/*
 * Chapter-list rendering for the Details screen (Maintainability M1: split out of DetailsScreen.kt).
 * Holds the chapter list adapter wiring, the filter-chip group, the pure chapter filter, the
 * per-row bookmark toggle, and the app-bar overflow menu — the chapter-list concerns — so
 * [showDetails] stays a layout orchestrator.
 */

/**
 * Overflow menu behind the app-bar "more" icon. Holds the secondary/tertiary story actions that
 * don't warrant a primary button: opening the source site, chapter selection, EPUB settings, and
 * text cleanup.
 */
internal fun ScreenHost.showDetailsOverflow(story: Story) {
    val isBusy = storyOperation?.storyId == story.id
    val options = mutableListOf<Pair<String, () -> Unit>>()
    options += "Open Source" to {
        runCatching { app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(story.sourceUrl))) }
            .onFailure { toast("No app available to open source") }
    }
    val provider = SourceRegistry.getProvider(story.sourceUrl)
    if (StoryActionGuards.canSync(story) && provider?.supportsLatestChapterSync == true) {
        options += "Full Sync" to {
            if (isBusy) toast("Please wait for the current operation to finish") else syncStory(story, StorySyncMode.Full)
        }
    }
    if (StoryActionGuards.canQueueDownloads(story)) {
        options += "Select Chapters" to {
            if (isBusy) toast("Please wait for the current operation to finish") else showChapterSelection(story.id)
        }
    }
    options += "EPUB Settings" to {
        if (isBusy) toast("Please wait for the current operation to finish") else showEpubConfigDialog(story)
    }
    options += "Legacy EPUBs" to {
        if (isBusy) toast("Please wait for the current operation to finish") else showLegacyEpubs(story.id)
    }
    // Pure navigation (no story mutation), so no busy guard is needed.
    options += "Trends" to { showTrends(story.id, null) }
    options += "Apply Text Cleanup" to {
        if (isBusy) toast("Please wait for the current operation to finish") else applyCleanup(story)
    }
    options += "Delete Novel" to {
        if (isBusy) {
            toast("Please wait for the current operation to finish")
        } else {
            confirm("Delete \"${story.title}\"? This action cannot be undone.") {
                scope.launch {
                    repository.deleteStory(story.id)
                    showLibrary()
                }
            }
        }
    }
    showStyledOptionsDialog("More options", options)
}

internal fun ScreenHost.renderChapterList(
    story: Story,
    list: androidx.recyclerview.widget.RecyclerView,
    query: String,
    filter: String,
    chipsContainer: ViewGroup,
    onPick: (String) -> Unit,
    chapterStatuses: Map<String, com.vinicius741.webnovelarchiver.domain.model.DownloadJobStatus> = emptyMap(),
    header: View? = null,
) {
    val displayedChapters = filterDetailsChapters(story, query, filter)
    val isEmptyState = displayedChapters.isEmpty()
    val existingChapterAdapter =
        when (val adapter = list.adapter) {
            is ChapterListAdapter -> adapter
            is androidx.recyclerview.widget.ConcatAdapter -> adapter.adapters.filterIsInstance<ChapterListAdapter>().singleOrNull()
            else -> null
        }
    if (existingChapterAdapter != null) {
        existingChapterAdapter.update(
            if (isEmptyState) listOf(-1 to Chapter(title = "No chapters match this view.")) else displayedChapters,
            story,
            isEmptyState,
            query,
            filter,
            chapterStatuses,
        )
        return
    }
    val initialChapters = if (isEmptyState) listOf(-1 to Chapter(title = "No chapters match this view.")) else displayedChapters
    val chapterAdapter =
        ChapterListAdapter(this, initialChapters, story, isEmptyState, list, query, filter, chapterStatuses, chipsContainer, onPick)
    list.adapter =
        if (header == null) chapterAdapter else androidx.recyclerview.widget.ConcatAdapter(DetailsHeaderAdapter(header), chapterAdapter)
}

/**
 * Pure chapter filter for the Details list — shared by [renderChapterList] (initial + filter/search)
 * and the in-place download refresh so both apply the identical view. Returns the matching
 * `(chapterIndex, chapter)` pairs, or an empty list when nothing matches (the caller decides
 * whether to substitute the "No chapters match" empty state).
 */
internal fun filterDetailsChapters(
    story: Story,
    query: String,
    filter: String,
): List<Pair<Int, Chapter>> {
    val bookmarkIndex = story.lastReadChapterId?.let { id -> story.chapters.indexOfFirst { it.id == id } } ?: -1
    return story.chapters
        .mapIndexed { index, chapter -> index to chapter }
        .filter { (_, chapter) -> chapter.title.contains(query, ignoreCase = true) }
        .filter { (index, chapter) ->
            when (filter) {
                "hideNonDownloaded" -> chapter.downloaded
                "hideAboveBookmark" -> bookmarkIndex < 0 || index >= bookmarkIndex
                else -> true
            }
        }
}

/**
 * Number of chapters the "From Bookmark" filter would show — i.e. chapters from the bookmarked
 * one onward (inclusive of the bookmark itself). Returns 0 when there is no valid bookmark, which
 * the chip group uses to disable the chip and skip the "(N)" suffix.
 */
internal fun fromBookmarkCount(story: Story): Int {
    val bookmarkIndex = story.lastReadChapterId?.let { id -> story.chapters.indexOfFirst { it.id == id } } ?: -1
    return if (bookmarkIndex < 0) 0 else story.chapters.size - bookmarkIndex
}

/**
 * Toggles the bookmark on [chapter] for [story] from the per-row bookmark button (which replaced the
 * old three-dot chapter overflow). Persists the new [Story.lastReadChapterId], rebuilds the filter
 * chips so the "From Bookmark (N)" count and enabled state stay in sync, and re-renders the list so
 * the tapped row's icon flips between the empty outline and the filled, primary-tinted bookmark.
 */
internal fun ScreenHost.toggleChapterBookmark(
    story: Story,
    chapter: Chapter,
    list: androidx.recyclerview.widget.RecyclerView,
    chipsContainer: ViewGroup,
    currentFilter: String,
    query: String,
    onPick: (String) -> Unit,
) {
    scope.launch {
        val updated = repository.toggleBookmark(story.id, chapter.id) ?: return@launch
        renderFilterChips(chipsContainer, currentFilter, fromBookmarkCount(updated), onPick)
        renderChapterList(updated, list, query, currentFilter, chipsContainer, onPick)
    }
}

/**
 * Rebuilds the filter chip group so the active mode re-highlights on every pick. The "From Bookmark"
 * chip shows a "(N)" suffix with the number of chapters from the bookmark onward, and is disabled
 * when there is no valid bookmark ([fromBookmarkCount] == 0), mirroring RN's `disabled={!hasBookmark}`.
 */
internal fun ScreenHost.renderFilterChips(
    container: ViewGroup,
    current: String,
    fromBookmarkCount: Int,
    onPick: (String) -> Unit,
) {
    container.removeAllViews()
    container.addView(makeChip(app, "All", current == "all") { onPick("all") })
    container.addView(makeChip(app, "Downloaded", current == "hideNonDownloaded") { onPick("hideNonDownloaded") })
    val bookmarkLabel = if (fromBookmarkCount > 0) "From Bookmark ($fromBookmarkCount)" else "From Bookmark"
    val bookmarkChip = makeChip(app, bookmarkLabel, current == "hideAboveBookmark") { onPick("hideAboveBookmark") }
    if (fromBookmarkCount == 0) {
        bookmarkChip.isEnabled = false
        bookmarkChip.alpha = 0.4f
    }
    container.addView(bookmarkChip)
}
