package com.vinicius741.webnovelarchiver

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
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
        addView(scroll(list), verticalFill())

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
        setPadding(0, dp(8), 0, 0)
    }
    val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
    }

    fun addTab(label: String, id: String?, isSelected: Boolean) {
        val text = makeText(context, label, Type.LABEL_LARGE, if (isSelected) ThemeManager.colors.primary else ThemeManager.colors.onSurfaceVariant).apply {
            typeface = Typeface.create(typeface, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
            setPadding(dp(4), dp(10), dp(4), dp(10))
            minWidth = dp(60)
            gravity = Gravity.CENTER
        }
        val underline = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(2)).apply { topMargin = dp(2) }
            setBackgroundColor(if (isSelected) ThemeManager.colors.primary else ThemeManager.colors.outlineVariant)
        }
        val tabContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, 0, dp(16), 0)
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
        setPadding(0, dp(8), 0, 0)
    }

    // Search + sort row
    val searchRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    searchRow.addView(search, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

    val sortIcon = if (sortAscending) R.drawable.wna_sort_ascending else R.drawable.wna_sort_descending
    val sortButton = context.iconButtonSmall(sortIcon, "Sort") {
        showSortDialog(context, sortOption, sortAscending, onSortChanged)
    }
    searchRow.addView(sortButton, LinearLayout.LayoutParams(dp(40), dp(40)).apply { leftMargin = dp(8) })
    filtersContainer.addView(searchRow)

    // Tag chips
    val labelsWithCounts = LibraryQuery.availableFilterLabelsWithCounts(stories, selectedTabId)
    if (labelsWithCounts.isNotEmpty()) {
        val tagScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, dp(12), 0, 0)
        }
        val tagRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        labelsWithCounts.take(10).forEach { (label, count) ->
            val chipLabel = "$label ($count)"
            val selected = selectedTags.contains(label)
            val chip = makeChip(context, chipLabel, selected) { onTagToggled(label) }
            tagRow.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = dp(8)
            })
        }
        tagScroll.addView(tagRow)
        filtersContainer.addView(tagScroll)
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
                layoutParams = FrameLayout.LayoutParams(dp(8), dp(8), Gravity.TOP or Gravity.END).apply { topMargin = dp(6); rightMargin = dp(6) }
                background = roundedBg(ThemeManager.colors.primary, dp(4).toFloat())
            })
        }
    }
    val headerRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
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

    val dialogView = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(16), context.dp(8), context.dp(16), context.dp(8))
    }

    val checkIcon = context.tintedIcon(R.drawable.wna_check, ThemeManager.colors.primary)
    var dialogRef: AlertDialog? = null

    options.forEach { (key, label) ->
        val isSelected = key == currentOption
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, context.dp(12), 0, context.dp(12))
            isClickable = true
            isFocusable = true
            background = selectableRipple(ThemeManager.colors.onSurface)
        }
        val check = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(context.dp(24), context.dp(24)).apply { rightMargin = context.dp(16) }
            if (isSelected) setImageDrawable(checkIcon)
        }
        val text = makeText(context, label, Type.BODY_LARGE, ThemeManager.colors.onSurface)
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
    val directionRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, context.dp(12), 0, context.dp(12))
        isClickable = true
        isFocusable = true
        background = selectableRipple(ThemeManager.colors.onSurface)
    }
    val directionIcon = context.tintedIcon(
        if (ascending) R.drawable.wna_sort_ascending else R.drawable.wna_sort_descending,
        ThemeManager.colors.primary,
    )
    val directionImage = ImageView(context).apply {
        layoutParams = LinearLayout.LayoutParams(context.dp(24), context.dp(24)).apply { rightMargin = context.dp(16) }
        setImageDrawable(directionIcon)
    }
    val directionText = makeText(context, if (ascending) "Ascending" else "Descending", Type.BODY_LARGE, ThemeManager.colors.onSurface)
    directionRow.addView(directionImage)
    directionRow.addView(directionText)
    directionRow.setOnClickListener {
        onChanged(currentOption to !ascending)
        dialogRef?.dismiss()
    }
    dialogView.addView(makeDivider(context))
    dialogView.addView(directionRow)

    dialogRef = AlertDialog.Builder(context)
        .setTitle("Sort by")
        .setView(dialogView)
        .setNegativeButton("Cancel", null)
        .show()
}

private fun defaultDirectionFor(option: String): Boolean = when (option) {
    "title" -> true
    else -> false
}

private fun Context.iconButtonSmall(iconRes: Int, desc: String, onClick: () -> Unit): ImageView {
    val size = dp(40)
    return ImageView(this).apply {
        contentDescription = desc
        setImageDrawable(tintedIcon(iconRes, ThemeManager.colors.onSurfaceVariant))
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(8), dp(8), dp(8), dp(8))
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
                        setPadding(0, dp(4), 0, 0)
                    })
                    SourceRegistry.getProvider(story.sourceUrl)?.let {
                        addView(makeText(context, it.name, Type.LABEL_SMALL, ThemeManager.colors.primary).apply {
                            setPadding(0, dp(4), 0, 0)
                        })
                    }
                    story.score?.takeIf { it.isNotBlank() }?.let { score ->
                        addView(scoreRow(score))
                    }
                    story.tags?.takeIf { it.isNotEmpty() }?.let { tags ->
                        addView(makeText(context, tags.take(5).joinToString("  •  "), Type.LABEL_SMALL, ThemeManager.colors.onSurfaceVariant).apply {
                            setPadding(0, dp(4), 0, 0)
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
                addView(makeProgress(context, story.downloadedChapters.toFloat() / story.totalChapters).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(4)).apply { topMargin = dp(12) }
                })
            }
        })
    }
}

private fun ScreenHost.showStoryActionsDialog(story: Story) {
    val items = mutableListOf("Open")
    if (StoryActionGuards.canSync(story)) items.add("Sync")
    items.add("Move")
    items.add("Delete")
    AlertDialog.Builder(app)
        .setTitle(story.title)
        .setItems(items.toTypedArray()) { _, which ->
            when (items[which]) {
                "Open" -> showDetails(story.id)
                "Sync" -> syncStory(story)
                "Move" -> showMoveStoryDialog(story)
                "Delete" -> confirm("Delete ${story.title}?") { storage.deleteStory(story.id); showLibrary() }
            }
        }
        .setNegativeButton("Cancel", null)
        .show()
}

internal fun ScreenHost.showLibrarySelection() {
    val stories = storage.getLibrary()
    val selectedIds = mutableSetOf<String>()
    screen(title = "Select Novels", onBack = { showLibrary() }) {
        flow {
            button("Move", Btn.TONAL, R.drawable.wna_folder) {
                if (selectedIds.isEmpty()) toast("No novels selected") else showMoveStoriesDialog(selectedIds.toList())
            }
            button("Delete", Btn.ERROR, R.drawable.wna_delete) {
                if (selectedIds.isEmpty()) {
                    toast("No novels selected")
                } else {
                    confirm("Delete ${selectedIds.size} selected novels?") {
                        selectedIds.forEach { storage.deleteStory(it) }
                        showLibrary()
                    }
                }
            }
        }
        addView(scroll(LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            stories.forEach { story ->
                val cb = CheckBox(app).apply {
                    text = "${story.title} - ${story.author}"
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) selectedIds.add(story.id) else selectedIds.remove(story.id)
                    }
                }
                styledCheckBox(cb)
                addView(cb)
            }
        }), verticalFill())
    }
}

internal fun ScreenHost.showMoveStoriesDialog(storyIds: List<String>) {
    val tabs = storage.getTabs().sortedBy { it.order }
    val labels = listOf("Unassigned") + tabs.map { it.name }
    AlertDialog.Builder(app)
        .setTitle("Move ${storyIds.size} Novels")
        .setItems(labels.toTypedArray()) { _, which ->
            val tabId = tabs.getOrNull(which - 1)?.id
            storyIds.forEach { id ->
                storage.getStory(id)?.let { story ->
                    story.tabId = tabId
                    storage.addOrUpdateStory(story)
                }
            }
            showLibrary()
        }
        .setNegativeButton("Cancel", null)
        .show()
}

internal fun ScreenHost.showAddStory() {
    val tabs = storage.getTabs().sortedBy { it.order }
    screen(title = "Add Story", subtitle = "Paste a story URL to import", onBack = { showLibrary() }, scrollable = true) {
        val url = makeField(context, "", "Royal Road or Scribble Hub story URL", android.text.InputType.TYPE_TEXT_VARIATION_URI)
        addView(url)
        section("Save to tab")
        val tabSpinner = Spinner(context)
        val tabLabels = listOf("Unassigned") + tabs.map { it.name }
        tabSpinner.adapter = ArrayAdapter(app, android.R.layout.simple_spinner_dropdown_item, tabLabels)
        if (tabs.isNotEmpty()) addView(tabSpinner)
        flow {
            button("Fetch Story", Btn.FILLED, R.drawable.wna_download) {
                val tabId = tabs.getOrNull(tabSpinner.selectedItemPosition - 1)?.id
                syncStory(url.text.toString(), tabId)
            }
        }
        section("Or browse")
        flow {
            button("Royal Road", Btn.TONAL, R.drawable.wna_globe) { showBrowser("https://www.royalroad.com") }
            button("Scribble Hub", Btn.TONAL, R.drawable.wna_globe) { showBrowser("https://www.scribblehub.com") }
        }
    }
}

internal fun ScreenHost.showMoveStoryDialog(story: Story) {
    val tabs = storage.getTabs().sortedBy { it.order }
    val labels = listOf("Unassigned") + tabs.map { it.name }
    AlertDialog.Builder(app)
        .setTitle("Move Novel")
        .setItems(labels.toTypedArray()) { _, which ->
            story.tabId = tabs.getOrNull(which - 1)?.id
            storage.addOrUpdateStory(story)
            showLibrary()
        }
        .setNegativeButton("Cancel", null)
        .show()
}
