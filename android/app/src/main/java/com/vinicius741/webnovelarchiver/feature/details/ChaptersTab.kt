package com.vinicius741.webnovelarchiver.feature.details

import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.app.appContainer
import com.vinicius741.webnovelarchiver.domain.model.ChapterFilterSettings
import com.vinicius741.webnovelarchiver.domain.model.DownloadJobStatus
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.story.StoryActionGuards
import com.vinicius741.webnovelarchiver.download.DownloadDetailsPlanning
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.CardListDecoration
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeButton
import com.vinicius741.webnovelarchiver.ui.makeChip
import com.vinicius741.webnovelarchiver.ui.makeSearchField
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.rememberListPosition
import com.vinicius741.webnovelarchiver.ui.tintedIcon
import kotlinx.coroutines.launch

/*
 * The Chapters tab: search, filter chips with counts (All / Downloaded / From bookmark / Not
 * downloaded / Removed from source), the shown count with jump-to-bookmark and Select, then the
 * recycled chapter rows. The header rides as the list's first item so the whole tab scrolls as one.
 */

/**
 * Controls the Chapters tab header; [clearSearch] backs the screen's Back intercept, and [refresh]
 * takes a download event's story so the rows, chip counts and shown count stay current together.
 */
internal class ChaptersTab(
    val list: RecyclerView,
    val hasQuery: () -> Boolean,
    val clearSearch: () -> Unit,
    val refresh: (Story, Map<String, DownloadJobStatus>, Set<String>) -> Unit,
)

@Suppress("LongMethod") // One linear tab build; its helpers are the chips and the tools row.
internal fun ScreenHost.buildChaptersTab(
    container: LinearLayout,
    initial: Story,
    memoryKey: String,
): ChaptersTab {
    var story = initial
    var query = routeMemory.queries[memoryKey].orEmpty()
    var filter = chapterFilterFor(story.id)
    val jobs = repository.queue().filter { it.storyId == story.id }
    val statuses = DownloadDetailsPlanning.chapterJobStatuses(jobs)
    val waiting = waitingChapterIds(jobs, app.appContainer.downloadPacer.snapshots.value.values, app.appContainer.time.nowMillis())
    val list =
        RecyclerView(app).apply {
            layoutManager = LinearLayoutManager(app)
            clipToPadding = false
            setPadding(0, 0, 0, dp(Spacing.XL))
        }
    val search =
        makeSearchField(app, "Search chapters").apply {
            setText(query)
            setCompoundDrawablesRelativeWithIntrinsicBounds(
                app.tintedIcon(R.drawable.wna_search, ThemeManager.colors.onSurfaceVariant),
                null,
                null,
                null,
            )
            compoundDrawablePadding = dp(Spacing.SM)
        }
    val chips = LinearLayout(app).apply { orientation = LinearLayout.HORIZONTAL }
    val countLine = makeText(app, "", Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant)
    val tools =
        LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(countLine, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    val header =
        LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(Spacing.MD + 2), 0, dp(Spacing.XS))
            addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(
                HorizontalScrollView(app).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(chips)
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin =
                        dp(Spacing.SM + 2)
                },
            )
            addView(
                tools,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin =
                        dp(Spacing.XS)
                },
            )
        }
    lateinit var adapter: ChapterListAdapter
    lateinit var renderHeader: () -> Unit

    fun render() {
        adapter.show(story, query, filter)
        renderHeader()
    }

    fun pick(mode: String) {
        filter = mode
        rememberChapterFilter(story.id, mode)
        render()
    }

    fun jumpToBookmark() {
        val id = story.lastReadChapterId ?: return
        if (adapter.positionOf(id) < 0) {
            query = ""
            search.setText("")
            pick(ALL_FILTER)
        }
        val position = adapter.positionOf(id)
        if (position >= 0) (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(position + 1, dp(Spacing.XL * 2))
    }
    renderHeader = {
        renderChapterChips(chips, story, filter, ::pick)
        val shown = adapter.shownCount()
        countLine.text = "%,d chapter%s".format(shown, if (shown == 1) "" else "s")
        renderChapterTools(tools, story, ::jumpToBookmark)
    }
    adapter =
        ChapterListAdapter(this, story) { chapter ->
            scope.launch {
                story = repository.toggleBookmark(story.id, chapter.id) ?: return@launch
                render()
            }
        }
    adapter.show(story, query, filter, statuses, waiting)
    renderHeader()
    search.doAfterTextChanged {
        query = it?.toString().orEmpty()
        routeMemory.queries[memoryKey] = query
        render()
    }
    list.adapter = ConcatAdapter(DetailsHeaderAdapter(header), adapter)
    list.addItemDecoration(CardListDecoration(app) { position -> position >= 1 })
    container.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    rememberListPosition(list)
    if (StoryTabMemory.focusChapterSearch == story.id) {
        StoryTabMemory.focusChapterSearch = null
        search.post {
            search.requestFocus()
            (app.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(search, 0)
        }
    }
    return ChaptersTab(list, { query.isNotEmpty() }, { search.setText("") }) { latest, latestStatuses, latestWaiting ->
        story = latest
        adapter.show(latest, query, filter, latestStatuses, latestWaiting)
        renderHeader()
    }
}

/** The shown count's row: jump to the bookmark (ivory) and Select chapters. */
private fun ScreenHost.renderChapterTools(
    tools: LinearLayout,
    story: Story,
    onJump: () -> Unit,
) {
    while (tools.childCount > 1) tools.removeViewAt(1)
    val colors = ThemeManager.colors
    val bookmarkIndex = story.lastReadChapterId?.let { id -> story.chapters.indexOfFirst { it.id == id } } ?: -1
    if (bookmarkIndex >= 0) {
        tools.addView(
            makeButton(app, "Ch. ${bookmarkIndex + 1}", Btn.TEXT, R.drawable.wna_bookmark) { onJump() }.apply {
                setTextColor(colors.bookmark)
                setCompoundDrawablesRelativeWithIntrinsicBounds(app.tintedIcon(R.drawable.wna_bookmark, colors.bookmark), null, null, null)
                contentDescription = "Jump to your bookmark, chapter ${bookmarkIndex + 1}"
            },
        )
    }
    if (StoryActionGuards.canQueueDownloads(story)) {
        tools.addView(makeButton(app, "Select", Btn.TEXT, R.drawable.wna_checklist) { showChapterSelection(story.id) })
    }
}

/** Filter chips with counts; "Not downloaded" and "Removed from source" appear only when they apply. */
private fun ScreenHost.renderChapterChips(
    container: LinearLayout,
    story: Story,
    current: String,
    onPick: (String) -> Unit,
) {
    container.removeAllViews()
    val total = story.chapters.size
    val downloaded = story.chapters.count { it.downloaded }
    val removed = story.chapters.count { it.isRemovedFromSource }
    val fromBookmark = ChapterFilterPlanning.fromBookmarkCount(story)
    val chips =
        buildList {
            add(Triple(ALL_FILTER, "All", total))
            add(Triple(DOWNLOADED_FILTER, "Downloaded", downloaded))
            if (fromBookmark > 0) add(Triple(FROM_BOOKMARK_FILTER, "From bookmark", fromBookmark))
            if (total - downloaded > 0) add(Triple(NOT_DOWNLOADED_FILTER, "Not downloaded", total - downloaded))
            if (removed > 0) add(Triple(REMOVED_FROM_SOURCE_FILTER, "Removed from source", removed))
        }
    val shown = chips.firstOrNull { it.first == current }?.first ?: ALL_FILTER
    chips.forEach { (mode, label, count) ->
        container.addView(
            makeChip(app, "$label  ${"%,d".format(count)}", mode == shown) { onPick(mode) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd =
                    dp(Spacing.SM)
            },
        )
    }
}

/** The filter to open with: this run's choice for the story, else the saved preference. */
private fun ScreenHost.chapterFilterFor(storyId: String): String =
    StoryTabMemory.chapterFilters[storyId] ?: repository.chapterFilterSettings.get().filterMode

/** Saves a filter the settings file knows; "Not downloaded" is remembered only for this run. */
private fun ScreenHost.rememberChapterFilter(
    storyId: String,
    mode: String,
) {
    if (mode == NOT_DOWNLOADED_FILTER) {
        StoryTabMemory.chapterFilters[storyId] = mode
        return
    }
    StoryTabMemory.chapterFilters.remove(storyId)
    scope.launch { repository.chapterFilterSettings.save(ChapterFilterSettings(mode)) }
}
