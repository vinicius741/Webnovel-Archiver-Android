package com.vinicius741.webnovelarchiver.feature.library

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.model.Tab
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.WrapLayout
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeButton
import com.vinicius741.webnovelarchiver.ui.makeCountedChip
import com.vinicius741.webnovelarchiver.ui.makeSheetSection
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.ripple
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.showBottomSheet
import com.vinicius741.webnovelarchiver.ui.tintedIcon

/** Sort keys offered in the sheet, in display order, with their full labels. */
internal val LIBRARY_SORT_OPTIONS =
    listOf(
        "default" to "Default (Smart)",
        "title" to "Title",
        "lastUpdated" to "Last Updated",
        "dateAdded" to "Date Added",
        "totalChapters" to "Chapter Count",
        "score" to "Score",
        "patreonMonthly" to "Patreon Earnings",
        "patreonMembers" to "Patreon Paid Members",
    )

/** Tags shown before "Show all"; selected tags always show. */
private const val TAG_PREVIEW_COUNT = 18

/**
 * The Library's one Sort & filter sheet: sort key, direction, and source and tag filters for the
 * current tab. Every change applies to the list at once through [onChange]; the footer shows how
 * many novels match.
 */
internal fun ScreenHost.showLibrarySortFilterSheet(
    stories: List<Story>,
    tabs: List<Tab>,
    current: () -> LibraryFilterState,
    onChange: (LibraryFilterState) -> Unit,
) {
    val body = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL }
    var showAllTags = false
    val showButton = makeButton(app, "Show", Btn.FILLED) {}

    fun refresh() {
        val state = current()
        val apply: (LibraryFilterState) -> Unit = { next ->
            onChange(next)
            refresh()
        }
        body.removeAllViews()
        renderSortSection(body, state, apply)
        renderFilterSections(body, stories, state, showAllTags, onToggleShowAll = {
            showAllTags = !showAllTags
            refresh()
        }, apply)
        val matches = state.applyTo(stories).size
        showButton.text = if (matches == 1) "Show 1 novel" else "Show $matches novels"
    }

    val footer =
        LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                makeButton(app, "Clear", Btn.OUTLINED) {
                    onChange(current().copy(selectedTags = emptySet()))
                    refresh()
                },
            )
            addView(
                showButton,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart =
                        dp(Spacing.SM + 2)
                },
            )
        }
    refresh()
    val handle =
        showBottomSheet(
            app,
            title = "Sort & filter",
            subtitle = "${tabName(current().selectedTabId, tabs)} tab",
            body = body,
            footer = footer,
        )
    showButton.setOnClickListener { handle.dismiss() }
}

private fun tabName(
    tabId: String?,
    tabs: List<Tab>,
): String =
    when (tabId) {
        null -> "Unassigned"
        LibraryTabSelection.ALL_TAB_ID -> "All"
        else -> tabs.firstOrNull { it.id == tabId }?.name ?: "All"
    }

private fun ScreenHost.renderSortSection(
    body: LinearLayout,
    state: LibraryFilterState,
    onChange: (LibraryFilterState) -> Unit,
) {
    val change = onChange
    val selectedKey = LibraryFiltersPlanning.normalizeSortOption(state.sortOption)
    body.addView(makeSheetSection(app, "Sort by"))
    val chips =
        WrapLayout(app).apply {
            horizontalSpacingDp = Spacing.SM
            verticalSpacingDp = Spacing.SM
            setPadding(dp(Spacing.SM), dp(Spacing.XS), dp(Spacing.SM), 0)
        }
    LIBRARY_SORT_OPTIONS.forEach { (key, label) ->
        chips.addView(
            makeCountedChip(app, label, null, key == selectedKey) {
                if (key !=
                    selectedKey
                ) {
                    change(state.copy(sortOption = key, sortAscending = LibraryFiltersPlanning.defaultDirectionFor(key)))
                }
            },
            ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
    }
    body.addView(chips)
    body.addView(
        directionControl(state.sortAscending) { ascending ->
            if (ascending !=
                state.sortAscending
            ) {
                change(state.copy(sortAscending = ascending))
            }
        },
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(Spacing.SM), dp(Spacing.MD), dp(Spacing.SM), 0)
        },
    )
}

@Suppress("LongParameterList")
private fun ScreenHost.renderFilterSections(
    body: LinearLayout,
    stories: List<Story>,
    state: LibraryFilterState,
    showAllTags: Boolean,
    onToggleShowAll: () -> Unit,
    onChange: (LibraryFilterState) -> Unit,
) {
    val change = onChange
    val (sources, tags) = LibraryQuery.availableFilterGroups(stories, state.selectedTabId, state.query, state.selectedTags)

    fun toggle(label: String) {
        val next = state.selectedTags.toMutableSet()
        if (!next.add(label)) next.remove(label)
        change(state.copy(selectedTags = next))
    }
    if (sources.isNotEmpty()) {
        body.addView(makeSheetSection(app, "Source"))
        body.addView(
            chipWrap(sources) { (label, count) ->
                makeCountedChip(app, label, count, label in state.selectedTags, R.drawable.wna_globe) { toggle(label) }
            },
        )
    }
    if (tags.isEmpty()) return
    body.addView(makeSheetSection(app, "Tags · match all"))
    val shown =
        if (showAllTags) {
            tags
        } else {
            tags.take(TAG_PREVIEW_COUNT) + tags.drop(TAG_PREVIEW_COUNT).filter { it.first in state.selectedTags }
        }
    body.addView(
        chipWrap(shown) { (label, count) ->
            makeCountedChip(app, label, count, label in state.selectedTags) { toggle(label) }
        },
    )
    if (tags.size > TAG_PREVIEW_COUNT) {
        body.addView(
            makeButton(app, if (showAllTags) "Show fewer" else "Show all ${tags.size} tags", Btn.TEXT) {
                onToggleShowAll()
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin =
                    dp(Spacing.XS)
            },
        )
    }
}

private fun ScreenHost.chipWrap(
    items: List<Pair<String, Int>>,
    chip: (Pair<String, Int>) -> View,
): View =
    WrapLayout(app).apply {
        horizontalSpacingDp = Spacing.SM
        verticalSpacingDp = Spacing.SM
        setPadding(dp(Spacing.SM), dp(Spacing.XS), dp(Spacing.SM), 0)
        items.forEach {
            addView(
                chip(it),
                ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }
    }

/** Descending | Ascending segmented control. */
private fun ScreenHost.directionControl(
    ascending: Boolean,
    onPick: (Boolean) -> Unit,
): View {
    val colors = ThemeManager.colors
    val outer = dp(SEGMENT_HEIGHT_DP / 2 + 3).toFloat()
    val inner = dp(SEGMENT_HEIGHT_DP / 2).toFloat()
    return LinearLayout(app).apply {
        orientation = LinearLayout.HORIZONTAL
        background = roundedBg(colors.elevation2, outer)
        setPadding(dp(Spacing.XS), dp(Spacing.XS), dp(Spacing.XS), dp(Spacing.XS))
        listOf(false to "Descending", true to "Ascending").forEach { (value, label) ->
            val selected = value == ascending
            val fg = if (selected) colors.onSurface else colors.onSurfaceVariant
            addView(
                LinearLayout(app).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    background =
                        ripple(
                            roundedBg(if (selected) colors.elevation5 else android.graphics.Color.TRANSPARENT, inner),
                            inner,
                            colors.onSurface,
                        )
                    isClickable = true
                    isFocusable = true
                    isSelected = selected
                    contentDescription = label
                    addView(
                        ImageView(app).apply {
                            setImageDrawable(
                                app.tintedIcon(if (value) R.drawable.wna_sort_ascending else R.drawable.wna_sort_descending, fg),
                            )
                        },
                        LinearLayout.LayoutParams(dp(SEGMENT_ICON_DP), dp(SEGMENT_ICON_DP)).apply { marginEnd = dp(Spacing.XS + 2) },
                    )
                    addView(makeText(app, label, Type.LABEL_LARGE, fg))
                    setOnClickListener { onPick(value) }
                },
                LinearLayout.LayoutParams(0, dp(SEGMENT_HEIGHT_DP), 1f),
            )
        }
    }
}

private const val SEGMENT_HEIGHT_DP = 38
private const val SEGMENT_ICON_DP = 18
