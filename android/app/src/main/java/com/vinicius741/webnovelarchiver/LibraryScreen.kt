package com.vinicius741.webnovelarchiver

import android.app.AlertDialog
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import com.vinicius741.webnovelarchiver.core.LibraryQuery
import com.vinicius741.webnovelarchiver.core.SourceRegistry
import com.vinicius741.webnovelarchiver.core.Story
import com.vinicius741.webnovelarchiver.core.StoryActionGuards
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
            AppBarAction(R.drawable.wna_settings, "Settings") { showSettings() },
        ),
        fab = { showAddStory() },
    ) {
        if (stories.isEmpty()) {
            addView(makeEmptyState(context, "No novels yet. Tap + to add a Royal Road or Scribble Hub story.", R.drawable.wna_menu_book))
            return@screen
        }
        val tabs = storage.getTabs().sortedBy { it.order }
        val search = makeSearchField(context, "Search title or author")
        addView(search)
        var selectedTabId: String? = "__all__"
        val selectedTags = mutableSetOf<String>()
        var sortOption = "updated"
        var sortAscending = false
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val rerender = {
            renderLibraryList(stories, list, search.text.toString(), selectedTabId, selectedTags, sortOption, sortAscending)
        }
        section("Tabs")
        flow {
            chip("All", selectedTabId == "__all__") { selectedTabId = "__all__"; rerender() }
            chip("Unassigned", selectedTabId == null) { selectedTabId = null; rerender() }
            tabs.forEach { tab ->
                chip(tab.name, selectedTabId == tab.id) { selectedTabId = tab.id; rerender() }
            }
        }
        section("Sort")
        flow {
            chip("Default", sortOption == "default") { if (sortOption == "default") sortAscending = !sortAscending else { sortOption = "default"; sortAscending = false }; rerender() }
            chip("Title", sortOption == "title") { if (sortOption == "title") sortAscending = !sortAscending else { sortOption = "title"; sortAscending = true }; rerender() }
            chip("Added", sortOption == "dateAdded") { if (sortOption == "dateAdded") sortAscending = !sortAscending else { sortOption = "dateAdded"; sortAscending = false }; rerender() }
            chip("Updated", sortOption == "lastUpdated") { if (sortOption == "lastUpdated") sortAscending = !sortAscending else { sortOption = "lastUpdated"; sortAscending = false }; rerender() }
            chip("Chapters", sortOption == "totalChapters") { if (sortOption == "totalChapters") sortAscending = !sortAscending else { sortOption = "totalChapters"; sortAscending = false }; rerender() }
            chip("Score", sortOption == "score") { if (sortOption == "score") sortAscending = !sortAscending else { sortOption = "score"; sortAscending = false }; rerender() }
            button("Select", Btn.TEXT, R.drawable.wna_check) { showLibrarySelection() }
        }
        val filterLabels = LibraryQuery.availableFilterLabels(stories, selectedTabId)
        if (filterLabels.isNotEmpty()) {
            section("Sources & Tags")
            flow {
                filterLabels.take(10).forEach { label ->
                    chip(label, selectedTags.contains(label)) {
                        if (!selectedTags.add(label)) selectedTags.remove(label)
                        rerender()
                    }
                }
            }
            if (filterLabels.size > 10) text("${filterLabels.size - 10} more filters available on individual stories.", Type.BODY_SMALL)
        }
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
    if (selectedTags.isNotEmpty()) {
        list.addView(makeText(app, "Filtered by: ${selectedTags.joinToString(", ")}", Type.LABEL_MEDIUM, ThemeManager.colors.secondary).apply {
            setPadding(dp(2), dp(10), dp(2), dp(6))
        })
    }
    visible.forEach { story ->
        list.addView(list.card {
            val content = row {
                addView(coverImage(story, widthDp = 72, heightDp = 108, tapToOpen = false))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(makeText(context, "${story.title}${if (story.isArchived == true) "" else ""}", Type.TITLE_MEDIUM, ThemeManager.colors.onSurface).apply {
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                    })
                    addView(makeText(context, "by ${story.author}", Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant).apply {
                        setPadding(0, dp(2), 0, 0)
                    })
                    SourceRegistry.getProvider(story.sourceUrl)?.let {
                        addView(makeText(context, it.name, Type.LABEL_SMALL, ThemeManager.colors.primary).apply { setPadding(0, dp(2), 0, 0) })
                    }
                    story.score?.takeIf { it.isNotBlank() }?.let { score ->
                        addView(LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            addView(ImageView(context).apply {
                                setImageDrawable(context.tintedIcon(R.drawable.wna_star, ThemeManager.colors.tertiary))
                                layoutParams = LinearLayout.LayoutParams(dp(13), dp(13))
                            })
                            addView(makeText(context, score, Type.LABEL_LARGE, ThemeManager.colors.onSurface).apply {
                                setPadding(dp(3), 0, 0, 0)
                            })
                        })
                    }
                    story.tags?.takeIf { it.isNotEmpty() }?.let {
                        addView(makeText(context, it.take(5).joinToString("  •  "), Type.LABEL_SMALL, ThemeManager.colors.onSurfaceVariant).apply {
                            setPadding(0, dp(2), 0, 0)
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
            if (story.totalChapters > 0) {
                addView(makeProgress(context, story.downloadedChapters.toFloat() / story.totalChapters).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(4)).apply { topMargin = dp(10) }
                })
            }
            addView(makeText(context, "${story.downloadedChapters}/${story.totalChapters} chapters • ${story.status.displayName()}", Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant).apply {
                setPadding(0, dp(6), 0, dp(4))
            })
            flow {
                button("Open", Btn.TEXT, R.drawable.wna_book_open) { showDetails(story.id) }
                if (StoryActionGuards.canSync(story)) {
                    button("Sync", Btn.TONAL, R.drawable.wna_refresh) { syncStory(story) }
                }
                button("Move", Btn.TEXT, R.drawable.wna_folder) { showMoveStoryDialog(story) }
                button("Delete", Btn.TEXT, R.drawable.wna_delete) { confirm("Delete ${story.title}?") { storage.deleteStory(story.id); showLibrary() } }
            }
        })
    }
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
        val url = makeField(context, "", "Royal Road or Scribble Hub story URL", InputType.TYPE_TEXT_VARIATION_URI)
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
