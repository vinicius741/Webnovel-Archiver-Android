package com.vinicius741.webnovelarchiver.feature.updates

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import com.vinicius741.webnovelarchiver.domain.story.UpdatesPlanning
import com.vinicius741.webnovelarchiver.domain.story.UpdatesPlanning.Entry
import com.vinicius741.webnovelarchiver.domain.story.UpdatesPlanning.Overview
import com.vinicius741.webnovelarchiver.domain.story.UpdatesPlanning.Section
import com.vinicius741.webnovelarchiver.feature.library.showAddStory
import com.vinicius741.webnovelarchiver.feature.story.queueDownload
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.AppBarAction
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.RAIL_MIN_WIDTH_DP
import com.vinicius741.webnovelarchiver.ui.SCREEN_SIDE_PADDING_DP
import com.vinicius741.webnovelarchiver.ui.ScreenChrome
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeButton
import com.vinicius741.webnovelarchiver.ui.makeCountedChip
import com.vinicius741.webnovelarchiver.ui.makeEmptyState
import com.vinicius741.webnovelarchiver.ui.makeFullWidthButton
import com.vinicius741.webnovelarchiver.ui.makeListGroup
import com.vinicius741.webnovelarchiver.ui.makeSectionHeader
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.makeTopLevelAppBar
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.verticalFill
import kotlinx.coroutines.launch

/** The Updates filters: the overview, or one section in full. */
internal enum class UpdatesFilter(
    val label: String,
    val section: Section?,
) {
    ALL("All", null),
    MAY_BE_OUT("May be out", Section.MAY_BE_OUT),
    TO_DOWNLOAD("To download", Section.TO_DOWNLOAD),
    EXPECTED("Expected", Section.EXPECTED_SOON),
    LATE("Late", Section.RUNNING_LATE),
    SYNCED("Synced", Section.RECENTLY_SYNCED),
}

/** Rebinds one rendered row with fresh data (or its live sync status), without rebuilding the list. */
internal class UpdatesRowBinder(
    private var entry: Entry,
    private val apply: (Entry) -> Unit,
) {
    fun bind(next: Entry = entry) {
        entry = next
        apply(next)
    }
}

/**
 * The Updates destination: release forecasts for every novel (what may be out now, what is
 * expected this week, who is running late), chapters still to download, and recent syncs, with
 * per-novel and "sync all that may be out" actions on the shared manual sync path.
 */
internal fun ScreenHost.showUpdates() {
    activeStory = null
    rerender = { showUpdates() }
    val host = this
    val state = updatesState
    val stories = repository.library()
    screen(AppRoute.Updates, "Updates", chrome = ScreenChrome.IMMERSIVE) {
        val appBar =
            makeTopLevelAppBar(
                "Updates",
                forecastSubtitle(stories.size),
                listOf(AppBarAction(R.drawable.wna_sync, "Sync all that may be out") { syncAllMayBeOut() }),
            )
        addView(appBar.view)
        if (stories.isEmpty()) {
            addView(
                makeEmptyState(
                    context,
                    title = "No novels yet",
                    message = "Add a story and its release forecast shows up here.",
                    iconRes = R.drawable.wna_updates,
                    actionLabel = "Add a story",
                    onAction = { showAddStory() },
                ),
            )
            return@screen
        }
        val chips = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
        addView(chips)
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val scroller =
            ScrollView(context).apply {
                isFillViewport = true
                clipToPadding = false
                addView(body)
            }
        addView(scroller, verticalFill())
        val savedScroll = routeMemory.scrollPositions[navigator.current.stableKey] ?: 0
        if (savedScroll > 0) scroller.post { scroller.scrollTo(0, savedScroll) }
        UpdatesScreenView(host, chips, body, scroller, appBar::setSubtitle).also { view ->
            state.onChange = { if (body.isAttachedToWindow) view.refresh() }
            state.onStatus = { id, _ -> view.patchStatus(id) }
            view.refresh()
            observeUpdatesData(body, view)
        }
    }
}

private fun forecastSubtitle(count: Int): String = if (count == 1) "Forecast for 1 novel" else "Forecasts for $count novels"

/** Re-plans after library or queue changes, coalescing bursts (a download tick per chapter). */
private fun ScreenHost.observeUpdatesData(
    body: View,
    view: UpdatesScreenView,
) {
    var seen = repository.downloadState.value.let { it.libraryVersion to it.queueVersion }
    var pending = false
    screenObserver =
        scope.launch {
            repository.downloadState.collect { snapshot ->
                val versions = snapshot.libraryVersion to snapshot.queueVersion
                if (versions == seen || !body.isAttachedToWindow) return@collect
                seen = versions
                if (pending) return@collect
                pending = true
                body.postDelayed({
                    pending = false
                    if (body.isAttachedToWindow) view.refresh()
                }, REFRESH_COALESCE_MS)
            }
        }
}

/**
 * Renders the Updates body. [refresh] re-plans; when the same rows are still showing it rebinds
 * them in place (no scroll jump or flicker), otherwise it rebuilds the body.
 */
private class UpdatesScreenView(
    private val host: ScreenHost,
    private val chipScroll: HorizontalScrollView,
    private val body: LinearLayout,
    private val scroller: ScrollView,
    private val setSubtitle: (String?) -> Unit,
) {
    private val rowBinders = mutableMapOf<String, UpdatesRowBinder>()
    private var structure: List<String>? = null
    private var heroSlot: LinearLayout? = null
    private var jobsByStory: Map<String, List<DownloadJob>> = emptyMap()

    fun refresh() {
        val now = System.currentTimeMillis()
        val stories = host.repository.library()
        val overview = UpdatesPlanning.overview(stories, now)
        jobsByStory = host.repository.queue().groupBy { it.storyId }
        setSubtitle(forecastSubtitle(stories.size))
        renderChips(overview)
        val filter = host.updatesState.filter
        val nextStructure = structureOf(overview, filter)
        if (nextStructure == structure) {
            heroSlot?.let { renderLead(it, overview, filter) }
            overview.sections.forEach { (section, entries) ->
                entries.forEach { entry -> rowBinders[rowKey(section, entry.story.id)]?.bind(entry) }
            }
            return
        }
        structure = nextStructure
        rebuild(overview, filter)
    }

    /** Binders read the live status from updatesState, so rebinding just this novel's rows is enough. */
    fun patchStatus(storyId: String) {
        Section.entries.forEach { section -> rowBinders[rowKey(section, storyId)]?.bind() }
    }

    private fun structureOf(
        overview: Overview,
        filter: UpdatesFilter,
    ): List<String> =
        buildList {
            add(filter.name)
            add(host.wideUpdatesLayout().toString())
            visibleSections(overview, filter).forEach { (section, entries) ->
                add(section.name)
                entries.forEach { add(it.story.id) }
            }
        }

    private fun visibleSections(
        overview: Overview,
        filter: UpdatesFilter,
    ): List<Pair<Section, List<UpdatesPlanning.Entry>>> =
        if (filter.section != null) {
            listOf(filter.section to overview.entries(filter.section))
        } else {
            overview.sections.entries.filter { it.value.isNotEmpty() }.map { (section, entries) ->
                section to
                    entries.take(overviewLimit(section))
            }
        }

    private fun renderChips(overview: Overview) {
        val filter = host.updatesState.filter
        val scrollX = chipScroll.scrollX
        chipScroll.removeAllViews()
        val row =
            LinearLayout(host.app).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(host.dp(SCREEN_SIDE_PADDING_DP), host.dp(Spacing.XS), host.dp(SCREEN_SIDE_PADDING_DP), host.dp(Spacing.SM))
            }
        UpdatesFilter.entries.forEach { option ->
            val count = option.section?.let { overview.count(it) }?.takeIf { option != UpdatesFilter.SYNCED }
            row.addView(
                makeCountedChip(host.app, option.label, count, option == filter) { selectFilter(option) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd =
                        host.dp(Spacing.SM)
                },
            )
        }
        chipScroll.addView(row)
        chipScroll.post { chipScroll.scrollTo(scrollX, 0) }
    }

    fun selectFilter(filter: UpdatesFilter) {
        if (host.updatesState.filter == filter) return
        host.updatesState.filter = filter
        refresh()
        scroller.scrollTo(0, 0)
    }

    private fun rebuild(
        overview: Overview,
        filter: UpdatesFilter,
    ) {
        body.removeAllViews()
        rowBinders.clear()
        val side = host.dp(SCREEN_SIDE_PADDING_DP)
        body.setPadding(side, host.dp(Spacing.SM), side, host.dp(Spacing.XL))
        val lead = LinearLayout(host.app).apply { orientation = LinearLayout.VERTICAL }
        heroSlot = lead
        body.addView(lead)
        renderLead(lead, overview, filter)
        val sections = visibleSections(overview, filter)
        if (filter.section != null) {
            val entries = sections.single().second
            if (entries.isEmpty()) body.addView(host.emptyStateFor(filter)) else body.addView(sectionList(filter.section, entries))
            return
        }
        if (sections.isEmpty()) {
            body.addView(
                makeEmptyState(
                    host.app,
                    title = "No forecasts yet",
                    message = "A forecast appears once a novel has a few dated chapters. Sync your novels to build them.",
                    iconRes = R.drawable.wna_updates,
                ),
            )
            return
        }
        val columns = if (host.wideUpdatesLayout()) 2 else 1
        val columnViews =
            (0 until columns).map {
                LinearLayout(host.app).apply { orientation = LinearLayout.VERTICAL }
            }
        if (columns > 1) {
            body.addView(
                LinearLayout(host.app).apply {
                    orientation = LinearLayout.HORIZONTAL
                    columnViews.forEachIndexed { index, column ->
                        addView(
                            column,
                            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                                if (index > 0) marginStart = host.dp(Spacing.LG + Spacing.XS)
                            },
                        )
                    }
                },
            )
        } else {
            body.addView(columnViews.single())
        }
        sections.forEachIndexed { index, (section, entries) ->
            val column = columnViews[index % columns]
            column.addView(sectionHeader(section, overview.count(section)))
            if (section == Section.MAY_BE_OUT) {
                column.addView(
                    makeText(
                        host.app,
                        "Based on each novel's usual release rhythm.",
                        Type.BODY_SMALL,
                        ThemeManager.colors.onSurfaceVariant,
                    ).apply {
                        setPadding(host.dp(Spacing.XS), 0, host.dp(Spacing.XS), host.dp(Spacing.SM + 2))
                    },
                )
            }
            column.addView(sectionList(section, entries))
        }
    }

    private fun sectionHeader(
        section: Section,
        total: Int,
    ): View {
        val row =
            LinearLayout(host.app).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        row.addView(
            makeSectionHeader(host.app, sectionTitle(section)),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        if (total > overviewLimit(section)) {
            val filter = UpdatesFilter.entries.first { it.section == section }
            row.addView(
                makeButton(host.app, "See all $total", Btn.TEXT) { selectFilter(filter) }
                    .apply {
                        minHeight = host.dp(SEE_ALL_HEIGHT_DP)
                        minimumHeight = host.dp(SEE_ALL_HEIGHT_DP)
                        setPadding(host.dp(Spacing.SM), 0, host.dp(Spacing.SM), 0)
                    },
            )
        }
        return row
    }

    private fun sectionList(
        section: Section,
        entries: List<UpdatesPlanning.Entry>,
    ): View =
        makeListGroup(host.app).apply {
            entries.forEach { entry ->
                addView(
                    host.makeUpdatesRow(entry, section, { System.currentTimeMillis() }, { jobsByStory[it].orEmpty() }, rowBinders),
                )
            }
        }

    private fun renderLead(
        slot: LinearLayout,
        overview: Overview,
        filter: UpdatesFilter,
    ) {
        slot.removeAllViews()
        when (filter) {
            UpdatesFilter.ALL -> slot.addView(host.makeForecastCard(overview, ::selectFilter))
            UpdatesFilter.MAY_BE_OUT ->
                if (host.updatesState.batch != null || overview.count(Section.MAY_BE_OUT) > 0) {
                    slot.addView(host.makeSyncAllBlock(overview.count(Section.MAY_BE_OUT)))
                }
            UpdatesFilter.TO_DOWNLOAD ->
                if (overview.count(Section.TO_DOWNLOAD) > 0) slot.addView(host.downloadAllButton(overview))
            else -> Unit
        }
        if (slot.childCount > 0) (slot.getChildAt(0).layoutParams as? LinearLayout.LayoutParams)?.bottomMargin = host.dp(Spacing.MD)
    }
}

private fun ScreenHost.wideUpdatesLayout(): Boolean {
    val config = app.resources.configuration
    val rail = if (config.screenWidthDp >= RAIL_MIN_WIDTH_DP) RAIL_WIDTH_DP else 0
    return config.screenWidthDp - rail >= TWO_COLUMN_MIN_DP
}

private fun ScreenHost.downloadAllButton(overview: Overview): View {
    val chapters = overview.missingChapters
    val novels = overview.entries(Section.TO_DOWNLOAD)
    return makeFullWidthButton(
        app,
        "Download all $chapters chapters",
        Btn.TONAL,
        R.drawable.wna_download,
        0,
    ) {
        confirm(
            "Queue $chapters chapters from ${novels.size} ${if (novels.size == 1) "novel" else "novels"}? This may take a long time and use significant data and storage.",
            confirmLabel = "Download all",
        ) {
            novels.forEach { entry ->
                repository.story(entry.story.id)?.let { latest ->
                    val indexes = UpdatesPlanning.missingChapterIndexes(latest)
                    if (indexes.isNotEmpty()) queueDownload(latest, indexes)
                }
            }
        }
    }
}

private fun ScreenHost.emptyStateFor(filter: UpdatesFilter): View {
    val (title, message) =
        when (filter) {
            UpdatesFilter.MAY_BE_OUT -> "Nothing waiting" to "No novel looks overdue right now."
            UpdatesFilter.TO_DOWNLOAD -> "All downloaded" to "Every known chapter is on this device."
            UpdatesFilter.EXPECTED ->
                "Nothing expected this week" to
                    "Novels with a regular schedule show up here before their next chapter."
            UpdatesFilter.LATE -> "Nobody is running late" to "Novels that go quiet for longer than usual show up here."
            UpdatesFilter.SYNCED, UpdatesFilter.ALL -> "No recent syncs" to "Novels synced in the last week show up here."
        }
    return makeEmptyState(app, title = title, message = message, iconRes = R.drawable.wna_updates)
}

internal fun sectionTitle(section: Section): String =
    when (section) {
        Section.MAY_BE_OUT -> "May be out"
        Section.TO_DOWNLOAD -> "To download"
        Section.EXPECTED_SOON -> "Expected soon"
        Section.RUNNING_LATE -> "Running late"
        Section.RECENTLY_SYNCED -> "Recently synced"
    }

private fun overviewLimit(section: Section): Int = if (section == Section.MAY_BE_OUT || section == Section.EXPECTED_SOON) 4 else 3

private const val REFRESH_COALESCE_MS = 400L
private const val RAIL_WIDTH_DP = 84
private const val TWO_COLUMN_MIN_DP = 600
private const val SEE_ALL_HEIGHT_DP = 36
