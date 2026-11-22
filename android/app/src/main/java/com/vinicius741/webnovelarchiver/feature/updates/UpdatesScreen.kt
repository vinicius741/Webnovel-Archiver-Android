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
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.navigation.ShellDestination
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.RAIL_MIN_WIDTH_DP
import com.vinicius741.webnovelarchiver.ui.SCREEN_SIDE_PADDING_DP
import com.vinicius741.webnovelarchiver.ui.ScreenChrome
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.bindWhileAttached
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.dropUnusedBaselineAlignment
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

/** The Updates screen's model, kept while Updates is in the history. */
private fun ScreenHost.updatesModel(): UpdatesScreenModel =
    screenModels.get(AppRoute.Updates.stableKey) { UpdatesScreenModel(HostUpdatesData(this)) }

/**
 * The Updates destination: release forecasts for every novel (what may be out now, what is
 * expected this week, who is running late), chapters still to download, and recent syncs, with
 * per-novel and "sync all that may be out" actions on the shared manual sync path. Built from
 * [UpdatesScreenModel]'s state; see [UpdatesRenderer] for how it follows it.
 */
internal fun ScreenHost.showUpdates() {
    activeStory = null
    rerender = { showUpdates() }
    // screen() navigates to this route before it prunes the store, so the model is kept.
    val model = updatesModel()
    UpdatesRenderer(this, model, model.state.value).render()
}

private fun forecastSubtitle(count: Int): String = if (count == 1) "Forecast for 1 novel" else "Forecasts for $count novels"

/**
 * One render of Updates, bound to [model] while its views are on screen. When the same rows are
 * still showing, a new state rebinds them in place (no scroll jump or flicker) and redraws the lead
 * card; a sync's progress rebinds only that novel's rows; otherwise the body is rebuilt. An empty
 * library becoming non-empty (or the reverse) rebuilds the screen.
 */
private class UpdatesRenderer(
    private val host: ScreenHost,
    private val model: UpdatesScreenModel,
    /** The state this render shows. */
    private var applied: UpdatesUiState,
) : UpdatesLeadActions,
    UpdatesRowSource {
    private val app = host.app
    private val rowBinders = mutableMapOf<String, UpdatesRowBinder>()
    private var structure: List<String>? = null
    private var heroSlot: LinearLayout? = null
    private lateinit var chipScroll: HorizontalScrollView
    private lateinit var body: LinearLayout
    private lateinit var scroller: ScrollView
    private var setSubtitle: (String?) -> Unit = {}

    fun render() {
        val state = applied
        lateinit var root: View
        host.screen(AppRoute.Updates, "Updates", chrome = ScreenChrome.IMMERSIVE) {
            root = this
            val appBar =
                host.makeTopLevelAppBar(
                    "Updates",
                    forecastSubtitle(state.storyCount),
                    // The forecast card (and the May be out filter) hold the one Sync all button.
                    emptyList(),
                )
            setSubtitle = appBar::setSubtitle
            addView(appBar.view)
            if (state.storyCount == 0) {
                addView(
                    makeEmptyState(
                        context,
                        title = "No novels yet",
                        message = "Add a story and its release forecast shows up here.",
                        iconRes = R.drawable.wna_updates,
                        actionLabel = "Add a story",
                        onAction = { host.showAddStory() },
                    ),
                )
                return@screen
            }
            chipScroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
            addView(chipScroll)
            body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            scroller =
                ScrollView(context).apply {
                    isFillViewport = true
                    clipToPadding = false
                    addView(body)
                }
            addView(scroller, verticalFill())
            val savedScroll = host.routeMemory.scrollPositions[host.navigator.current.stableKey] ?: 0
            if (savedScroll > 0) scroller.post { scroller.scrollTo(0, savedScroll) }
            show(state)
        }
        root.bindWhileAttached(host.scope, model.state) { apply(it) }
    }

    private fun apply(next: UpdatesUiState) {
        if (next == applied) return
        val previous = applied
        applied = next
        if ((next.storyCount == 0) != (previous.storyCount == 0)) {
            if (host.navigator.current == AppRoute.Updates) host.showUpdates()
            return
        }
        if (next.storyCount == 0) return
        if (next == previous.copy(syncing = next.syncing)) {
            // Only sync progress moved: rebind just the rows whose line changed.
            (previous.syncing.keys + next.syncing.keys)
                .filter { previous.syncing[it] != next.syncing[it] }
                .forEach(::patchStatus)
            return
        }
        show(next)
        if (next.filter != previous.filter) scroller.scrollTo(0, 0)
    }

    private fun show(state: UpdatesUiState) {
        val overview = state.overview
        // The badge is otherwise planned only when the library changes, so it can trail the clock.
        host.shell.setBadge(ShellDestination.UPDATES, state.mayBeOut)
        setSubtitle(forecastSubtitle(state.storyCount))
        renderChips(state)
        val nextStructure = structureOf(overview, state.filter)
        if (nextStructure == structure) {
            heroSlot?.let { renderLead(it, state) }
            overview.sections.forEach { (section, entries) ->
                entries.forEach { entry -> rowBinders[rowKey(section, entry.story.id)]?.bind(entry) }
            }
        } else {
            structure = nextStructure
            rebuild(state)
        }
        // Rebuilds after the first render happen outside screen(), which tidies only the first build.
        body.dropUnusedBaselineAlignment()
        chipScroll.dropUnusedBaselineAlignment()
    }

    /** Binders read the live status from [applied], so rebinding just this novel's rows is enough. */
    private fun patchStatus(storyId: String) {
        Section.entries.forEach { section -> rowBinders[rowKey(section, storyId)]?.bind() }
    }

    // Row source and lead actions

    override fun now(): Long = System.currentTimeMillis()

    override fun jobsFor(storyId: String): List<DownloadJob> = applied.jobsByStory[storyId].orEmpty()

    override fun syncingFor(storyId: String): String? = applied.syncing[storyId]

    override fun onSync(storyId: String) = model.sync(storyId)

    override fun onDownloadMissing(storyId: String) = model.downloadMissing(storyId)

    override fun onFilter(filter: UpdatesFilter) = model.selectFilter(filter)

    override fun onSyncAll() = model.syncAll()

    override fun onStop() = model.stopBatch()

    override fun onReview() = model.showPendingReviews()

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

    private fun renderChips(state: UpdatesUiState) {
        val filter = state.filter
        val scrollX = chipScroll.scrollX
        chipScroll.removeAllViews()
        val row =
            LinearLayout(app).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(host.dp(SCREEN_SIDE_PADDING_DP), host.dp(Spacing.XS), host.dp(SCREEN_SIDE_PADDING_DP), host.dp(Spacing.SM))
            }
        UpdatesFilter.entries.forEach { option ->
            val count = option.section?.let { state.overview.count(it) }?.takeIf { option != UpdatesFilter.SYNCED }
            row.addView(
                makeCountedChip(app, option.label, count, option == filter) { model.selectFilter(option) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd =
                        host.dp(Spacing.SM)
                },
            )
        }
        chipScroll.addView(row)
        chipScroll.post { chipScroll.scrollTo(scrollX, 0) }
    }

    private fun rebuild(state: UpdatesUiState) {
        val overview = state.overview
        val filter = state.filter
        body.removeAllViews()
        rowBinders.clear()
        val side = host.dp(SCREEN_SIDE_PADDING_DP)
        body.setPadding(side, host.dp(Spacing.SM), side, host.dp(Spacing.XL))
        val lead = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL }
        heroSlot = lead
        body.addView(lead)
        renderLead(lead, state)
        val sections = visibleSections(overview, filter)
        if (filter.section != null) {
            val entries = sections.single().second
            if (entries.isEmpty()) body.addView(emptyStateFor(filter)) else body.addView(sectionList(filter.section, entries))
            return
        }
        if (sections.isEmpty()) {
            body.addView(
                makeEmptyState(
                    app,
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
                LinearLayout(app).apply { orientation = LinearLayout.VERTICAL }
            }
        if (columns > 1) {
            body.addView(
                LinearLayout(app).apply {
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
                        app,
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
            LinearLayout(app).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        row.addView(
            makeSectionHeader(app, sectionTitle(section)),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        if (total > overviewLimit(section)) {
            val filter = UpdatesFilter.entries.first { it.section == section }
            row.addView(
                makeButton(app, "See all $total", Btn.TEXT) { model.selectFilter(filter) }
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
        makeListGroup(app).apply {
            entries.forEach { entry -> addView(host.makeUpdatesRow(entry, section, this@UpdatesRenderer, rowBinders)) }
        }

    private fun renderLead(
        slot: LinearLayout,
        state: UpdatesUiState,
    ) {
        val overview = state.overview
        slot.removeAllViews()
        when (state.filter) {
            UpdatesFilter.ALL -> slot.addView(app.makeForecastCard(overview, state.syncs, this))
            UpdatesFilter.MAY_BE_OUT ->
                if (state.syncs.batch != null || state.mayBeOut > 0) {
                    slot.addView(app.makeSyncAllBlock(state.mayBeOut, state.syncs, this))
                }
            UpdatesFilter.TO_DOWNLOAD ->
                if (overview.count(Section.TO_DOWNLOAD) > 0) slot.addView(downloadAllButton(overview))
            else -> Unit
        }
        if (slot.childCount > 0) (slot.getChildAt(0).layoutParams as? LinearLayout.LayoutParams)?.bottomMargin = host.dp(Spacing.MD)
    }

    private fun downloadAllButton(overview: Overview): View {
        val chapters = overview.missingChapters
        val novels = overview.entries(Section.TO_DOWNLOAD)
        return makeFullWidthButton(
            app,
            "Download all $chapters chapters",
            Btn.TONAL,
            R.drawable.wna_download,
            0,
        ) {
            host.confirm(
                "Queue $chapters chapters from ${novels.size} ${if (novels.size == 1) "novel" else "novels"}? This may take a long time and use significant data and storage.",
                confirmLabel = "Download all",
            ) { model.downloadAllMissing() }
        }
    }

    private fun emptyStateFor(filter: UpdatesFilter): View {
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
}

private fun ScreenHost.wideUpdatesLayout(): Boolean {
    val config = app.resources.configuration
    val rail = if (config.screenWidthDp >= RAIL_MIN_WIDTH_DP) RAIL_WIDTH_DP else 0
    return config.screenWidthDp - rail >= TWO_COLUMN_MIN_DP
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

private const val RAIL_WIDTH_DP = 84
private const val TWO_COLUMN_MIN_DP = 600
private const val SEE_ALL_HEIGHT_DP = 36
