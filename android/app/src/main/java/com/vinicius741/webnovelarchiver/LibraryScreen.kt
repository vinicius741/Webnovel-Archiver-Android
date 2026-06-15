package com.vinicius741.webnovelarchiver

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import com.vinicius741.webnovelarchiver.core.LibraryQuery
import com.vinicius741.webnovelarchiver.core.LibraryTabSelection
import com.vinicius741.webnovelarchiver.core.ScreenLayoutResult
import com.vinicius741.webnovelarchiver.core.SourceRegistry
import com.vinicius741.webnovelarchiver.core.Story
import com.vinicius741.webnovelarchiver.core.StoryActionGuards
import com.vinicius741.webnovelarchiver.core.Tab
import com.vinicius741.webnovelarchiver.core.libraryMaxContentWidth
import com.vinicius741.webnovelarchiver.ui.*

internal fun ScreenHost.showLibrary() {
    activeStory = null
    // Re-render this screen when the window changes (fold/unfold/rotation) or the Large Screen Layout
    // setting toggles, so the column count reflows 1 → 2 → 3 live.
    rerender = { showLibrary() }
    val layoutResult = currentScreenLayout()
    val stories = storage.getLibrary()
    screen(
        title = "Library",
        subtitle = if (stories.isEmpty()) null else "${stories.size} novel${if (stories.size == 1) "" else "s"}",
        actions = listOf(
            AppBarAction(R.drawable.wna_globe, "Browser") { showBrowser("https://www.royalroad.com") },
            AppBarAction(R.drawable.wna_list, "Queue") { showQueue() },
            AppBarAction(R.drawable.wna_check, "Select") { showLibrarySelection() },
            AppBarAction(R.drawable.wna_settings, "Settings") { showSettings() },
        ),
        fab = { showAddStory() },
    ) {
        if (stories.isEmpty()) {
            addView(makeEmptyState(context, "No novels yet. Tap + to add a Royal Road or Scribble Hub story.", R.drawable.wna_menu_book))
            return@screen
        }

        val tabs = storage.getTabs().sortedBy { it.order }
        val search = makeSearchField(context, "Search stories")
        // The story list is a fixed-column [GridLayout] (1/2/3 from the size class) rather than a flat
        // vertical list, so the Fold's inner display shows multiple columns. columnCount is re-read on
        // every render so unfolding the device reflows the grid in place.
        val list = GridLayout(context).apply {
            columnCount = layoutResult.numColumns.coerceAtLeast(1)
            horizontalSpacingDp = Space.MD
            verticalSpacingDp = Space.SM + 2
        }

        // Restore the last-selected tab from persisted prefs (survives navigating away AND app restarts).
        // Resolve against the live tabs so a deleted tab's stale id falls back to All instead of an
        // empty, un-selectable view.
        val hasUnassigned = stories.any { it.tabId == null }
        var selectedTabId: String? = LibraryTabSelection.resolve(
            storage.getDisplayPreferences().libraryTabId,
            tabs,
            hasUnassigned,
        )
        val selectedTags = mutableSetOf<String>()
        var sortOption = "updated"
        var sortAscending = false

        fun persistTab(id: String?) {
            val encoded = LibraryTabSelection.encode(id)
            val display = storage.getDisplayPreferences()
            // Avoid a disk write when the selection hasn't actually changed.
            if (display.libraryTabId != encoded) {
                storage.saveDisplayPreferences(display.copy(libraryTabId = encoded))
            }
        }

        val rerender = {
            renderLibraryList(stories, list, layoutResult, search.text.toString(), selectedTabId, selectedTags, sortOption, sortAscending)
        }

        addView(makeLibraryTabBar(context, tabs, stories, selectedTabId) { newTabId ->
            selectedTabId = newTabId
            persistTab(newTabId)
            rerender()
        })
        addView(makeLibraryFilters(context, search, tabs.isNotEmpty(), stories, selectedTabId, selectedTags, sortOption, sortAscending, { newSort ->
            sortOption = newSort.first
            sortAscending = newSort.second
            rerender()
        }, { tag ->
            if (!selectedTags.add(tag)) selectedTags.remove(tag)
            rerender()
        }))
        val gridShell = MaxWidthFrameLayout(context).apply {
            // Center the grid and cap its width at the size-class content max (760/1040/1320dp) so it
            // never stretches edge-to-edge on tablets, matching the RN library layout.
            maxContentWidthDp = libraryMaxContentWidth(layoutResult.numColumns)
            addView(list, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        }
        addView(scroll(gridShell), verticalFill().apply { topMargin = dp(Space.LG) })

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                renderLibraryList(stories, list, layoutResult, s?.toString().orEmpty(), selectedTabId, selectedTags, sortOption, sortAscending)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        renderLibraryList(stories, list, layoutResult, "", selectedTabId, selectedTags, sortOption, sortAscending)
    }
}

private fun ScreenHost.makeLibraryTabBar(
    context: Context,
    tabs: List<Tab>,
    stories: List<Story>,
    selectedTabId: String?,
    onSelect: (String?) -> Unit,
): View {
    val unassignedCount = stories.count { it.tabId == null }
    val scroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
    }
    val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
    }

    // Keep each tab's id paired with its text + underline views so we can restyle the active
    // tab in place on selection, instead of rebuilding the whole bar (which would leave the
    // indicator stuck on the initially-selected tab).
    data class TabView(val id: String?, val text: TextView, val underline: View)
    val tabViews = mutableListOf<TabView>()
    var currentSelection = selectedTabId

    fun applySelection(id: String?) {
        currentSelection = id
        val colors = ThemeManager.colors
        tabViews.forEach { tab ->
            val selected = tab.id == id
            tab.text.setTextColor(if (selected) colors.primary else colors.onSurfaceVariant)
            tab.text.typeface = Typeface.create(tab.text.typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
            tab.underline.setBackgroundColor(if (selected) colors.primary else colors.outlineVariant)
        }
    }

    fun addTab(label: String, id: String?) {
        val text = makeText(context, label, Type.LABEL_LARGE, ThemeManager.colors.onSurfaceVariant).apply {
            setPadding(dp(Space.XS), dp(Space.XS), dp(Space.XS), dp(Space.XS))
            minWidth = dp(60)
            gravity = Gravity.CENTER
        }
        val underline = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(2))
        }
        val tabContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(text)
            addView(underline)
            isClickable = true
            isFocusable = true
            background = selectableRipple(ThemeManager.colors.onSurface)
            setOnClickListener {
                applySelection(id)
                onSelect(id)
            }
        }
        // Space tabs apart with an end margin (outside the ripple) rather than right padding, so the
        // label and underline stay centered within each tab's tap target instead of shifting left.
        val tabLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { marginEnd = dp(Space.SM + 2) }
        row.addView(tabContainer, tabLp)
        tabViews += TabView(id, text, underline)
    }

    addTab("All", LibraryTabSelection.ALL_TAB_ID)
    if (unassignedCount > 0) {
        addTab("Unassigned ($unassignedCount)", null)
    }
    tabs.forEach { tab ->
        addTab(tab.name, tab.id)
    }
    applySelection(currentSelection)
    scroll.addView(row)
    return scroll
}

private fun ScreenHost.makeLibraryFilters(
    context: Context,
    search: EditText,
    hasCustomTabs: Boolean,
    stories: List<Story>,
    selectedTabId: String?,
    selectedTags: Set<String>,
    sortOption: String,
    sortAscending: Boolean,
    onSortChanged: (Pair<String, Boolean>) -> Unit,
    onTagToggled: (String) -> Unit,
): View {
    val filtersContainer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }
    val filterTopMargin = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(Space.SM)
    }

    // Search + sort row
    val searchRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    searchRow.addView(search, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

    val sortIcon = if (sortAscending) R.drawable.wna_sort_ascending else R.drawable.wna_sort_descending
    // L2: a labeled chip communicates the active sort + direction instead of a bare, stateless icon.
    val sortLabel = sortOptionLabel(sortOption) + if (sortAscending) " ↑" else " ↓"
    val sortButton = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(Space.SM), dp(Space.SM), dp(Space.SM), dp(Space.SM))
        background = ripple(strokeBg(Color.TRANSPARENT, context.dp(ThemeManager.shapes.chipRadius).toFloat(), ThemeManager.colors.outline, context.dp(1)), context.dp(ThemeManager.shapes.chipRadius).toFloat(), ThemeManager.colors.onSurface)
        isClickable = true
        isFocusable = true
        setOnClickListener { showSortDialog(context, sortOption, sortAscending, onSortChanged) }
        addView(ImageView(context).apply {
            setImageDrawable(context.tintedIcon(sortIcon, ThemeManager.colors.onSurfaceVariant))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            layoutParams = LinearLayout.LayoutParams(dp(Space.SM + Space.XS + 2), dp(Space.SM + Space.XS + 2)).apply { rightMargin = dp(Space.XS + 2) }
        })
        addView(makeText(context, sortLabel, Type.LABEL_MEDIUM, ThemeManager.colors.onSurfaceVariant))
    }
    searchRow.addView(sortButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(Space.SM) })
    filtersContainer.addView(searchRow)

    // Tag chips — L4: render source filters (globe icon, filled) separately from genre tags so the
    // two filter kinds are visually distinguishable instead of one flat row of identical chips.
    val (sourceLabels, tagLabels) = LibraryQuery.availableFilterGroups(stories, selectedTabId)
    if (sourceLabels.isNotEmpty() || tagLabels.isNotEmpty()) {
        val tagScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
        }
        val tagRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        sourceLabels.take(4).forEach { (label, count) ->
            val selected = selectedTags.contains(label)
            val chip = makeSourceChip(context, label, count, selected) { onTagToggled(label) }
            tagRow.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = dp(Space.SM + 2)
            })
        }
        tagLabels.take(8).forEach { (label, count) ->
            val chipLabel = "$label ($count)"
            val selected = selectedTags.contains(label)
            val chip = makeChip(context, chipLabel, selected) { onTagToggled(label) }
            tagRow.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = dp(Space.SM + 2)
            })
        }
        tagScroll.addView(tagRow)
        filtersContainer.addView(tagScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(Space.SM)
        })
    }

    if (!hasCustomTabs) return filtersContainer.also { it.layoutParams = filterTopMargin }

    // Collapsible wrapper when tabs exist
    val wrapper = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    val hasActiveFilters = selectedTags.isNotEmpty() || search.text.isNotBlank()
    // The chevron is decorative only — its tap is handled by `toggleWrap`'s listener (below). It must
    // NOT be clickable/focusable, otherwise Android dispatches the touch to this ImageView (which has
    // its own no-op listener from iconButtonSmall), consumes it, and the parent never expands — leaving
    // the search/sort/tag filters trapped behind View.GONE and the whole filter row unresponsive.
    val toggleIcon = context.iconButtonSmall(R.drawable.wna_chevron_down, "Toggle filters") { }.apply {
        isClickable = false
        isFocusable = false
    }
    val toggleWrap = FrameLayout(context).apply {
        layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
            gravity = Gravity.END
        }
        addView(toggleIcon)
        if (hasActiveFilters) {
            addView(View(context).apply {
                layoutParams = FrameLayout.LayoutParams(dp(Space.SM), dp(Space.SM), Gravity.TOP or Gravity.END).apply { topMargin = dp(Space.XS + 2); rightMargin = dp(Space.XS + 2) }
                background = roundedBg(ThemeManager.colors.primary, dp(Space.XS).toFloat())
            })
        }
    }
    val headerRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    // L3: pair the chevron with a "Filters" label so the toggle is discoverable instead of a lone arrow.
    headerRow.addView(makeText(context, "Filters", Type.LABEL_MEDIUM, ThemeManager.colors.onSurfaceVariant).apply {
        setPadding(0, 0, dp(Space.XS + 2), 0)
    })
    if (hasActiveFilters) {
        headerRow.addView(makeText(context, "•", Type.LABEL_MEDIUM, ThemeManager.colors.onSurfaceVariant).apply { setPadding(0, 0, dp(Space.XS + 2), 0) })
    }
    headerRow.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
    headerRow.addView(toggleWrap)
    wrapper.addView(headerRow)
    wrapper.addView(filtersContainer)

    var expanded = false
    filtersContainer.visibility = View.GONE
    toggleWrap.setOnClickListener {
        expanded = !expanded
        filtersContainer.visibility = if (expanded) View.VISIBLE else View.GONE
        toggleIcon.animate().rotation(if (expanded) 180f else 0f).setDuration(200).start()
    }
    wrapper.layoutParams = filterTopMargin
    return wrapper
}

private fun showSortDialog(
    context: Context,
    currentOption: String,
    ascending: Boolean,
    onChanged: (Pair<String, Boolean>) -> Unit,
) {
    val options = listOf(
        "default" to "Default (Smart)",
        "title" to "Title",
        "lastUpdated" to "Last Updated",
        "dateAdded" to "Date Added",
        "totalChapters" to "Chapter Count",
        "score" to "Score",
    )

    val colors = ThemeManager.colors
    val shapes = ThemeManager.shapes
    val radiusPx = context.dp(shapes.dialogRadius).toFloat()

    val dialogView = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(24), context.dp(20), context.dp(24), context.dp(12))
        background = roundedBg(colors.surface, radiusPx)
        roundCorners(shapes.dialogRadius.toFloat())
    }

    dialogView.addView(makeText(context, "Sort by", Type.TITLE_LARGE, colors.onSurface))
    dialogView.addView(View(context).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(16))
    })

    val checkIcon = context.tintedIcon(R.drawable.wna_check, colors.primary)
    var dialogRef: AlertDialog? = null

    options.forEach { (key, label) ->
        val isSelected = key == currentOption
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, context.dp(12), 0, context.dp(12))
            isClickable = true
            isFocusable = true
            background = selectableRipple(colors.onSurface)
        }
        val check = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(context.dp(24), context.dp(24)).apply { rightMargin = context.dp(16) }
            if (isSelected) setImageDrawable(checkIcon)
        }
        val text = makeText(context, label, Type.BODY_LARGE, colors.onSurface)
        row.addView(check)
        row.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.setOnClickListener {
            val newAscending = if (key == currentOption) !ascending else defaultDirectionFor(key)
            onChanged(key to newAscending)
            dialogRef?.dismiss()
        }
        dialogView.addView(row)
    }

    // Direction toggle row
    dialogView.addView(makeDivider(context))
    val directionRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, context.dp(12), 0, context.dp(12))
        isClickable = true
        isFocusable = true
        background = selectableRipple(colors.onSurface)
    }
    val directionIcon = context.tintedIcon(
        if (ascending) R.drawable.wna_sort_ascending else R.drawable.wna_sort_descending,
        colors.primary,
    )
    val directionImage = ImageView(context).apply {
        layoutParams = LinearLayout.LayoutParams(context.dp(24), context.dp(24)).apply { rightMargin = context.dp(16) }
        setImageDrawable(directionIcon)
    }
    val directionText = makeText(context, if (ascending) "Ascending" else "Descending", Type.BODY_LARGE, colors.onSurface)
    directionRow.addView(directionImage)
    directionRow.addView(directionText)
    directionRow.setOnClickListener {
        onChanged(currentOption to !ascending)
        dialogRef?.dismiss()
    }
    dialogView.addView(directionRow)

    val cancelButton = makeButton(context, "Cancel", Btn.TEXT) { dialogRef?.dismiss() }
    dialogView.addView(LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
        setPadding(0, context.dp(8), 0, 0)
        addView(cancelButton)
    })

    dialogRef = AlertDialog.Builder(context)
        .setView(dialogView)
        .create()
    dialogRef.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    dialogRef.show()
}

private fun defaultDirectionFor(option: String): Boolean = when (option) {
    "title" -> true
    else -> false
}

/** Short human label for a sort option key, shown on the Library sort chip. */
private fun sortOptionLabel(option: String): String = when (option) {
    "title" -> "Title"
    "lastUpdated" -> "Updated"
    "dateAdded" -> "Added"
    "totalChapters" -> "Chapters"
    "score" -> "Score"
    else -> "Default"
}

private fun Context.iconButtonSmall(iconRes: Int, desc: String, onClick: () -> Unit): ImageView {
    val size = dp(40)
    return ImageView(this).apply {
        contentDescription = desc
        setImageDrawable(tintedIcon(iconRes, ThemeManager.colors.onSurfaceVariant))
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(Space.SM), dp(Space.SM), dp(Space.SM), dp(Space.SM))
        background = selectableRipple(ThemeManager.colors.onSurface)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(size, size)
    }
}

internal fun ScreenHost.renderLibraryList(
    stories: List<Story>,
    list: GridLayout,
    layout: ScreenLayoutResult,
    filter: String,
    selectedTabId: String?,
    selectedTags: Set<String>,
    sortOption: String,
    sortAscending: Boolean,
) {
    list.removeAllViews()
    // Re-apply the column count in case the window refolded since the grid was created.
    list.columnCount = layout.numColumns.coerceAtLeast(1)
    val visible = LibraryQuery.filterAndSort(stories, filter, selectedTabId, selectedTags, sortOption, sortAscending)
    if (visible.isEmpty()) {
        list.addView(makeEmptyState(app, "No novels match this view.", R.drawable.wna_search))
        return
    }
    val compact = layout.numColumns >= 2
    visible.forEach { story ->
        list.addView(list.card {
            val content = if (compact) buildCompactStoryCard(story) else buildStoryCard(story)
            content.background = selectableRipple(ThemeManager.colors.onSurface)
            content.isClickable = true
            content.setOnClickListener { showDetails(story.id) }
            content.setOnLongClickListener {
                showStoryActionsDialog(story)
                true
            }
            addView(content)
            if (story.totalChapters > 0) {
                // L5: show the download-count text alongside the thin progress bar so you don't have
                // to open the story to see "12 / 140 chapters".
                addView(makeProgressSummary(context, story.downloadedChapters, story.totalChapters).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(Space.MD) }
                })
            }
        })
    }
}

/** Full-width horizontal card content for the single-column library: 80×120 cover + stacked text. */
private fun ScreenHost.buildStoryCard(story: Story): LinearLayout {
    val row = LinearLayout(app).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    row.addView(coverImage(story, widthDp = 80, heightDp = 120, tapToOpen = false))
    row.addView(LinearLayout(app).apply {
        orientation = LinearLayout.VERTICAL
        addView(makeText(app, story.title, Type.TITLE_MEDIUM, ThemeManager.colors.onSurface).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        addView(makeText(app, "by ${story.author}", Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant).apply {
            setPadding(0, dp(Space.XS), 0, 0)
        })
        SourceRegistry.getProvider(story.sourceUrl)?.let {
            addView(makeText(app, it.name, Type.LABEL_SMALL, ThemeManager.colors.primary).apply {
                setPadding(0, dp(Space.XS), 0, 0)
            })
        }
        story.score?.takeIf { it.isNotBlank() }?.let { score ->
            addView(scoreRow(score))
        }
        story.tags?.takeIf { it.isNotEmpty() }?.let { tags ->
            addView(makeText(app, tags.take(5).joinToString("  •  "), Type.LABEL_SMALL, ThemeManager.colors.onSurfaceVariant).apply {
                setPadding(0, dp(Space.XS), 0, 0)
            })
        }
    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    if (story.isArchived == true) {
        row.addView(ImageView(app).apply {
            setImageDrawable(app.tintedIcon(R.drawable.wna_archive, ThemeManager.colors.primary))
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
        })
    }
    return row
}

/**
 * Vertical "compact" card content for the multi-column library grid: smaller 64×96 cover stacked
 * above the text, so a narrow grid cell still shows cover + title + author. Mirrors the RN StoryCard
 * compact mode (64×96 cover, tighter layout).
 */
private fun ScreenHost.buildCompactStoryCard(story: Story): LinearLayout = LinearLayout(app).apply {
    orientation = LinearLayout.VERTICAL
    addView(coverImage(story, widthDp = 64, heightDp = 96, tapToOpen = false).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    })
    addView(makeText(app, story.title, Type.TITLE_SMALL, ThemeManager.colors.onSurface).apply {
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
        setPadding(0, dp(Space.SM), 0, 0)
    })
    addView(makeText(app, "by ${story.author}", Type.LABEL_SMALL, ThemeManager.colors.onSurfaceVariant).apply {
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        setPadding(0, dp(Space.XS), 0, 0)
    })
    SourceRegistry.getProvider(story.sourceUrl)?.let {
        addView(makeText(app, it.name, Type.LABEL_SMALL, ThemeManager.colors.primary).apply {
            setPadding(0, dp(Space.XS), 0, 0)
        })
    }
    story.score?.takeIf { it.isNotBlank() }?.let { score ->
        addView(scoreRow(score).apply { setPadding(0, dp(Space.XS), 0, 0) })
    }
    if (story.isArchived == true) {
        addView(ImageView(app).apply {
            setImageDrawable(app.tintedIcon(R.drawable.wna_archive, ThemeManager.colors.primary))
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply { topMargin = dp(Space.XS) }
        })
    }
}

private fun ScreenHost.showStoryActionsDialog(story: Story) {
    val options = mutableListOf<Pair<String, () -> Unit>>("Open" to { showDetails(story.id) })
    if (StoryActionGuards.canSync(story)) options += "Sync" to { syncStory(story) }
    options += "Move" to { showMoveStoryDialog(story) }
    options += "Delete" to { confirm("Delete ${story.title}?") { storage.deleteStory(story.id); showLibrary() } }
    showStyledOptionsDialog(story.title, options)
}

internal fun ScreenHost.showLibrarySelection() {
    val stories = storage.getLibrary()
    val selectedIds = mutableSetOf<String>()
    screen(title = "Select Novels", onBack = { showLibrary() }) {
        // X3: select-all / deselect-all affordance.
        flow {
            button("Select All", Btn.TEXT, R.drawable.wna_check) {
                selectedIds.clear()
                selectedIds.addAll(stories.map { it.id })
                showLibrarySelection()
            }
            button("Deselect All", Btn.TEXT, R.drawable.wna_close) {
                selectedIds.clear()
                showLibrarySelection()
            }
        }
        addView(scroll(LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            // X1: card-style rows with title/author instead of bare CheckBoxes.
            stories.forEach { story ->
                addView(makeSelectableCardRow(
                    context,
                    title = story.title,
                    subtitle = story.author,
                    selected = selectedIds.contains(story.id),
                ) { checked ->
                    if (checked) selectedIds.add(story.id) else selectedIds.remove(story.id)
                })
            }
        }), verticalFill())
        // X2: bulk actions docked at the bottom as full-width primary CTAs.
        fullButton("Move ${selectedIds.size} Selected", Btn.TONAL, R.drawable.wna_folder, bottomMarginDp = 8) {
            if (selectedIds.isEmpty()) toast("No novels selected") else showMoveStoriesDialog(selectedIds.toList())
        }
        fullButton("Delete Selected", Btn.ERROR, R.drawable.wna_delete, bottomMarginDp = 0) {
            if (selectedIds.isEmpty()) {
                toast("No novels selected")
            } else {
                confirm("Delete ${selectedIds.size} selected novels?", confirmLabel = "Delete") {
                    selectedIds.forEach { storage.deleteStory(it) }
                    showLibrary()
                }
            }
        }
    }
}

internal fun ScreenHost.showMoveStoriesDialog(storyIds: List<String>) {
    val tabs = storage.getTabs().sortedBy { it.order }
    val tabOptions = listOf(null to "Unassigned") + tabs.map { it.id to it.name }
    val options = tabOptions.map { (tabId, label) ->
        label to {
            storyIds.forEach { id ->
                storage.getStory(id)?.let { story ->
                    story.tabId = tabId
                    storage.addOrUpdateStory(story)
                }
            }
            showLibrary()
        }
    }
    showStyledOptionsDialog("Move ${storyIds.size} Novels", options)
}

internal fun ScreenHost.showAddStory() {
    val tabs = storage.getTabs().sortedBy { it.order }
    screen(title = "Add Story", subtitle = "Paste a story URL to import", onBack = { showLibrary() }, scrollable = true) {
        val url = makeField(context, "", "Royal Road or Scribble Hub story URL", android.text.InputType.TYPE_TEXT_VARIATION_URI).apply {
            // Roomier vertical padding than the compact field style shared with search bars/dialogs,
            // so this primary URL input is easier to tap and read.
            setPadding(context.dp(Space.MD + 2), context.dp(Space.MD), context.dp(Space.MD + 2), context.dp(Space.MD))
        }
        // Paste button beside the field — mirrors the React Native app's content-paste affordance,
        // reading the system clipboard in one tap instead of long-pressing the field.
        val pasteButton = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val radiusPx = context.dp(ThemeManager.current.shapes.buttonRadius).toFloat()
            background = ripple(
                roundedBg(ThemeManager.colors.secondaryContainer, radiusPx),
                radiusPx,
                ThemeManager.colors.onSecondaryContainer,
            )
            isClickable = true
            isFocusable = true
            setPadding(context.dp(Space.MD), context.dp(Space.MD), context.dp(Space.MD), context.dp(Space.MD))
            addView(ImageView(context).apply {
                contentDescription = "Paste URL"
                setImageDrawable(context.tintedIcon(R.drawable.wna_paste, ThemeManager.colors.onSecondaryContainer))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            })
            setOnClickListener {
                val clip = clipboardText()?.trim()
                if (clip.isNullOrEmpty()) {
                    toast("Clipboard is empty")
                } else {
                    url.setText(clip)
                    url.setSelection(clip.length)
                }
            }
        }
        val urlRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(url, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(pasteButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(Space.SM)
            })
        }
        addView(urlRow)
        // A1: only render the "Save to tab" section when there are tabs to choose from.
        var tabSpinner: Spinner? = null
        if (tabs.isNotEmpty()) {
            section("Save to tab")
            tabSpinner = Spinner(context)
            val tabLabels = listOf("Unassigned") + tabs.map { it.name }
            tabSpinner.adapter = ArrayAdapter(app, android.R.layout.simple_spinner_dropdown_item, tabLabels)
            addView(tabSpinner)
        }
        // A2: the primary action is full-width for a consistent, large tap target.
        fullButton("Fetch Story", Btn.FILLED, R.drawable.wna_download, topMarginDp = Space.LG) {
            val spinnerPos = tabSpinner?.selectedItemPosition ?: 0
            val tabId = tabs.getOrNull(spinnerPos - 1)?.id
            syncStory(url.text.toString(), tabId)
        }
        // A3: the "Or browse" Royal Road / Scribble Hub buttons were removed — they open the same
        // Browser screen the app-bar globe does, just with a preset URL. Use the Browser to browse.
    }
}

internal fun ScreenHost.showMoveStoryDialog(story: Story) {
    val tabs = storage.getTabs().sortedBy { it.order }
    val tabOptions = listOf(null to "Unassigned") + tabs.map { it.id to it.name }
    val options = tabOptions.map { (tabId, label) ->
        label to {
            story.tabId = tabId
            storage.addOrUpdateStory(story)
            showLibrary()
        }
    }
    showStyledOptionsDialog("Move Novel", options)
}
