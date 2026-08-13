package com.vinicius741.webnovelarchiver.feature.updates

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.feature.library.LibraryFilterState
import com.vinicius741.webnovelarchiver.feature.library.LibraryTabSelection
import com.vinicius741.webnovelarchiver.feature.library.makeLibraryFilters
import com.vinicius741.webnovelarchiver.feature.library.makeLibraryTabBar
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.navigation.UpdateFollowSelectionState
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.applyCheckBoxTint
import com.vinicius741.webnovelarchiver.ui.button
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeEmptyState
import com.vinicius741.webnovelarchiver.ui.makeSearchField
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.size
import com.vinicius741.webnovelarchiver.ui.toast
import com.vinicius741.webnovelarchiver.ui.verticalFill
import kotlinx.coroutines.launch

internal fun ScreenHost.showUpdateFollowSelection() {
    activeStory = null
    rerender = { showUpdateFollowSelection() }
    val stories = UpdateTrackerPlanning.followableStories(repository.library())
    val selected =
        repository.getUpdateFollowedStoryIds().toMutableSet().apply {
            retainAll(stories.mapTo(hashSetOf()) { it.id })
        }
    scope.launch { repository.saveUpdateFollowedStoryIds(selected.toList()) }
    val state = updateFollowSelectionState
    val tabs = repository.getTabs().sortedBy { it.order }
    if (state.selectedTabId != null &&
        state.selectedTabId != LibraryTabSelection.ALL_TAB_ID &&
        tabs.none { it.id == state.selectedTabId }
    ) {
        state.selectedTabId = LibraryTabSelection.ALL_TAB_ID
    }
    lateinit var adapter: FollowStoryAdapter

    screen(
        route = AppRoute.UpdateFollowSelection,
        title = "Follow Updates",
        subtitle = "${selected.size} selected",
        onBack = { showUpdates() },
    ) {
        if (stories.isEmpty()) {
            addView(
                makeEmptyState(
                    context,
                    title = "Your library is empty",
                    message = "Import stories before setting up update tracking.",
                    iconRes = R.drawable.wna_menu_book,
                ),
            )
            return@screen
        }

        var applyFilters: () -> Unit = {}
        var refreshSelectedButton: () -> Unit = {}
        var refreshFilters: (String?, Set<String>) -> Unit = { _, _ -> }

        fun currentFilter(): LibraryFilterState =
            LibraryFilterState(
                query = state.query,
                selectedTabId = state.selectedTabId,
                selectedTags = state.selectedTags,
                sortOption = state.sortOption,
                sortAscending = state.sortAscending,
            )

        fun visibleStories(): List<Story> =
            UpdateTrackerPlanning.visibleFollowStories(
                stories,
                currentFilter(),
                selected,
                state.showSelectedOnly,
            )

        fun persistSelection() {
            scope.launch { repository.saveUpdateFollowedStoryIds(selected.toList()) }
            updateFollowSelectionSubtitle(selected.size)
            refreshSelectedButton()
        }

        val search =
            makeSearchField(context, "Search novels").apply {
                setText(state.query)
            }
        val filters =
            makeLibraryFilters(
                context,
                search,
                tabs.isNotEmpty(),
                stories,
                state.selectedTabId,
                state.selectedTags,
                state.sortOption,
                state.sortAscending,
                { newSort ->
                    state.sortOption = newSort.first
                    state.sortAscending = newSort.second
                    applyFilters()
                },
                { tag ->
                    val nextTags = state.selectedTags.toMutableSet()
                    if (!nextTags.add(tag)) nextTags.remove(tag)
                    state.selectedTags = nextTags
                    refreshFilters(state.selectedTabId, state.selectedTags)
                    applyFilters()
                },
            )
        refreshFilters = filters.rebuildChips
        val tabBar =
            makeLibraryTabBar(context, tabs, stories, state.selectedTabId) { newTabId ->
                state.selectedTabId = newTabId
                refreshFilters(state.selectedTabId, state.selectedTags)
                applyFilters()
            }
        addView(tabBar.view)
        addView(filters.view)
        search.doAfterTextChanged {
            state.query = it?.toString().orEmpty()
            filters.syncActiveFilters(state.selectedTags)
            refreshFilters(state.selectedTabId, state.selectedTags)
            applyFilters()
        }

        lateinit var selectedButton: Button
        val bulkRow =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        bulkRow
            .button("Select All", Btn.TONAL) {
                selected.addAll(visibleStories().map { it.id })
                persistSelection()
                applyFilters()
            }.layoutParams =
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f)
        bulkRow
            .button("Clear", Btn.OUTLINED) {
                selected.removeAll(visibleStories().map { it.id }.toSet())
                persistSelection()
                applyFilters()
            }.layoutParams =
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.8f).apply {
                marginStart = context.dp(Space.SM)
            }
        selectedButton =
            bulkRow
                .button(UpdateTrackerPlanning.selectedReviewLabel(selected.size, state.showSelectedOnly), Btn.OUTLINED) {
                    if (!state.showSelectedOnly && selected.isEmpty()) {
                        toast("No novels selected")
                        return@button
                    }
                    state.showSelectedOnly = !state.showSelectedOnly
                    refreshSelectedButton()
                    applyFilters()
                }.apply {
                    layoutParams =
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f).apply {
                            marginStart = context.dp(Space.SM)
                        }
                }
        refreshSelectedButton = {
            selectedButton.text = UpdateTrackerPlanning.selectedReviewLabel(selected.size, state.showSelectedOnly)
        }
        addView(
            bulkRow,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(Space.LG)
            },
        )

        val coversCheckBox =
            CheckBox(context).apply {
                text = "Show covers"
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, Type.LABEL_MEDIUM.size())
                setTextColor(ThemeManager.current.colors.onSurfaceVariant)
                isChecked = state.showCovers
                isSaveEnabled = false
                applyCheckBoxTint()
                setOnCheckedChangeListener { _, checked ->
                    state.showCovers = checked
                    scope.launch {
                        repository.saveDisplayPreferences(
                            repository.getDisplayPreferences().copy(showCoversOnUpdates = checked),
                        )
                    }
                    applyFilters()
                }
            }
        val novelsHeader =
            TextView(context).apply {
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, Type.TITLE_SMALL.size())
                typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
                setTextColor(ThemeManager.current.colors.onSurface)
                includeFontPadding = false
            }
        val headerRow =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(novelsHeader, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(coversCheckBox)
            }
        addView(
            headerRow,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(Space.MD)
            },
        )

        adapter =
            FollowStoryAdapter(this@showUpdateFollowSelection) { storyId, checked ->
                if (checked) selected.add(storyId) else selected.remove(storyId)
                persistSelection()
                applyFilters()
            }
        val emptyHost =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
            }
        val list =
            RecyclerView(context).apply {
                layoutManager = LinearLayoutManager(context)
                this.adapter = adapter
                itemAnimator = null
                overScrollMode = View.OVER_SCROLL_NEVER
            }
        addView(emptyHost, verticalFill())
        addView(list, verticalFill().apply { topMargin = context.dp(Space.SM) })

        applyFilters = {
            val visible = visibleStories()
            headerRow.visibility = if (visible.isEmpty()) View.GONE else View.VISIBLE
            novelsHeader.text = UpdateTrackerPlanning.followSelectionNovelsLabel(visible.size, stories.size)
            if (visible.isEmpty()) {
                emptyHost.removeAllViews()
                val (title, message) = UpdateTrackerPlanning.followSelectionEmptyCopy(state.showSelectedOnly)
                emptyHost.addView(
                    makeEmptyState(
                        context,
                        title = title,
                        message = message,
                        iconRes = if (state.showSelectedOnly) R.drawable.wna_check else R.drawable.wna_search,
                    ),
                )
                emptyHost.visibility = View.VISIBLE
                list.visibility = View.GONE
            } else {
                emptyHost.visibility = View.GONE
                list.visibility = View.VISIBLE
            }
            adapter.submit(visible, selected, state.showCovers)
        }
        applyFilters()
    }
}

private fun ScreenHost.updateFollowSelectionSubtitle(count: Int) {
    val root = frame.getChildAt(0) as? ViewGroup ?: return
    val appBar = root.getChildAt(0) as? ViewGroup ?: return
    val titleCol = (0 until appBar.childCount).map { appBar.getChildAt(it) }.filterIsInstance<LinearLayout>().firstOrNull() ?: return
    (titleCol.getChildAt(1) as? TextView)?.text = "$count selected"
}
