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
import com.vinicius741.webnovelarchiver.core.SourceRegistry
import com.vinicius741.webnovelarchiver.core.Story
import com.vinicius741.webnovelarchiver.core.StoryActionGuards
import com.vinicius741.webnovelarchiver.core.Tab
import com.vinicius741.webnovelarchiver.ui.*

internal fun ScreenHost.showLibrary() {
    activeStory = null
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
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        var selectedTabId: String? = "__all__"
        val selectedTags = mutableSetOf<String>()
        var sortOption = "updated"
        var sortAscending = false

        val rerender = {
            renderLibraryList(stories, list, search.text.toString(), selectedTabId, selectedTags, sortOption, sortAscending)
        }

        addView(makeLibraryTabBar(context, tabs, stories, selectedTabId) { newTabId ->
            selectedTabId = newTabId
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
        addView(scroll(list), verticalFill().apply { topMargin = dp(Space.LG) })

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                renderLibraryList(stories, list, s?.toString().orEmpty(), selectedTabId, selectedTags, sortOption, sortAscending)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        renderLibraryList(stories, list, "", selectedTabId, selectedTags, sortOption, sortAscending)
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

    fun addTab(label: String, id: String?, isSelected: Boolean) {
        val text = makeText(context, label, Type.LABEL_LARGE, if (isSelected) ThemeManager.colors.primary else ThemeManager.colors.onSurfaceVariant).apply {
            typeface = Typeface.create(typeface, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
            setPadding(dp(Space.XS), dp(Space.XS), dp(Space.XS), dp(Space.XS))
            minWidth = dp(60)
            gravity = Gravity.CENTER
        }
        val underline = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(2))
            setBackgroundColor(if (isSelected) ThemeManager.colors.primary else ThemeManager.colors.outlineVariant)
        }
        val tabContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, 0, dp(Space.SM), 0)
            addView(text)
            addView(underline)
            isClickable = true
            isFocusable = true
            background = selectableRipple(ThemeManager.colors.onSurface)
            setOnClickListener { onSelect(id) }
        }
        row.addView(tabContainer)
    }

    addTab("All", "__all__", selectedTabId == "__all__")
    if (unassignedCount > 0) {
        addTab("Unassigned ($unassignedCount)", null, selectedTabId == null)
    }
    tabs.forEach { tab ->
        addTab(tab.name, tab.id, selectedTabId == tab.id)
    }
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
                rightMargin = dp(Space.XS)
            })
        }
        tagLabels.take(8).forEach { (label, count) ->
            val chipLabel = "$label ($count)"
            val selected = selectedTags.contains(label)
            val chip = makeChip(context, chipLabel, selected) { onTagToggled(label) }
            tagRow.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = dp(Space.XS)
            })
        }
        tagScroll.addView(tagRow)
        filtersContainer.addView(tagScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(Space.SM)
        })
    }

    if (!hasCustomTabs) return filtersContainer

    // Collapsible wrapper when tabs exist
    val wrapper = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    val hasActiveFilters = selectedTags.isNotEmpty() || search.text.isNotBlank()
    val toggleIcon = context.iconButtonSmall(R.drawable.wna_chevron_down, "Toggle filters") { }
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
    list: LinearLayout,
    filter: String,
    selectedTabId: String?,
    selectedTags: Set<String>,
    sortOption: String,
    sortAscending: Boolean,
) {
    list.removeAllViews()
    val visible = LibraryQuery.filterAndSort(stories, filter, selectedTabId, selectedTags, sortOption, sortAscending)
    if (visible.isEmpty()) {
        list.addView(makeEmptyState(app, "No novels match this view.", R.drawable.wna_search))
        return
    }
    visible.forEach { story ->
        list.addView(list.card {
            val content = row {
                addView(coverImage(story, widthDp = 80, heightDp = 120, tapToOpen = false))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(makeText(context, story.title, Type.TITLE_MEDIUM, ThemeManager.colors.onSurface).apply {
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                    addView(makeText(context, "by ${story.author}", Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant).apply {
                        setPadding(0, dp(Space.XS), 0, 0)
                    })
                    SourceRegistry.getProvider(story.sourceUrl)?.let {
                        addView(makeText(context, it.name, Type.LABEL_SMALL, ThemeManager.colors.primary).apply {
                            setPadding(0, dp(Space.XS), 0, 0)
                        })
                    }
                    story.score?.takeIf { it.isNotBlank() }?.let { score ->
                        addView(scoreRow(score))
                    }
                    story.tags?.takeIf { it.isNotEmpty() }?.let { tags ->
                        addView(makeText(context, tags.take(5).joinToString("  •  "), Type.LABEL_SMALL, ThemeManager.colors.onSurfaceVariant).apply {
                            setPadding(0, dp(Space.XS), 0, 0)
                        })
                    }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (story.isArchived == true) {
                    addView(ImageView(context).apply {
                        setImageDrawable(context.tintedIcon(R.drawable.wna_archive, ThemeManager.colors.primary))
                        layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
                    })
                }
            }
            content.background = selectableRipple(ThemeManager.colors.onSurface)
            content.isClickable = true
            content.setOnClickListener { showDetails(story.id) }
            content.setOnLongClickListener {
                showStoryActionsDialog(story)
                true
            }
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
        val url = makeField(context, "", "Royal Road or Scribble Hub story URL", android.text.InputType.TYPE_TEXT_VARIATION_URI)
        addView(url)
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
        fullButton("Fetch Story", Btn.FILLED, R.drawable.wna_download) {
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
