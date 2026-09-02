package com.vinicius741.webnovelarchiver.feature.updates

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.model.UpdateFollowSettings
import com.vinicius741.webnovelarchiver.domain.story.FollowedNovelPlanning
import com.vinicius741.webnovelarchiver.feature.details.showDetails
import com.vinicius741.webnovelarchiver.feature.library.LibraryTabSelection
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
import com.vinicius741.webnovelarchiver.ui.verticalFill
import kotlinx.coroutines.launch

internal fun ScreenHost.showUpdateFollowSelection() {
    activeStory = null
    rerender = { showUpdateFollowSelection() }
    val stories = UpdateTrackerPlanning.followableStories(repository.library())
    val threshold = repository.getUpdateFollowSettings().thresholdChapters
    val followedCount = FollowedNovelPlanning.followedStories(stories, threshold).size
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
        title = "Following Review",
        subtitle = UpdateTrackerPlanning.reviewHeaderLabel(followedCount, stories.size),
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

        fun visibleEntries() =
            FollowedNovelPlanning.reviewEntries(
                UpdateTrackerPlanning.filterStories(
                    stories.filter { state.selectedTabId == LibraryTabSelection.ALL_TAB_ID || it.tabId == state.selectedTabId },
                    state.query,
                ),
                threshold,
            )

        val search =
            makeSearchField(context, "Search novels").apply {
                setText(state.query)
            }
        val tabBar =
            makeLibraryTabBar(context, tabs, stories, state.selectedTabId) { newTabId ->
                state.selectedTabId = newTabId
                applyFilters()
            }
        addView(tabBar.view)
        addView(
            search,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(Space.SM)
            },
        )
        search.doAfterTextChanged {
            state.query = it?.toString().orEmpty()
            applyFilters()
        }

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
        val controlsRow =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        controlsRow
            .button(UpdateTrackerPlanning.thresholdLabel(threshold), Btn.TONAL) {
                showUpdateThresholdDialog(threshold) { saved ->
                    scope.launch {
                        repository.saveUpdateFollowSettings(UpdateFollowSettings(saved))
                        rerender?.invoke()
                    }
                }
            }.layoutParams =
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f)
        coversCheckBox.layoutParams =
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.6f).apply {
                marginStart = context.dp(Space.SM)
            }
        controlsRow.addView(coversCheckBox)
        addView(
            controlsRow,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(Space.LG)
            },
        )

        val novelsHeader =
            TextView(context).apply {
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, Type.TITLE_SMALL.size())
                typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
                setTextColor(ThemeManager.current.colors.onSurface)
                includeFontPadding = false
            }
        addView(
            novelsHeader,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(Space.MD)
            },
        )

        adapter =
            FollowStoryAdapter(this@showUpdateFollowSelection) { storyId -> showDetails(storyId) }
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
            val visible = visibleEntries()
            novelsHeader.visibility = if (visible.isEmpty()) View.GONE else View.VISIBLE
            novelsHeader.text = UpdateTrackerPlanning.reviewNovelsLabel(visible.size, stories.size)
            if (visible.isEmpty()) {
                emptyHost.removeAllViews()
                val (title, message) = UpdateTrackerPlanning.reviewEmptyCopy()
                emptyHost.addView(
                    makeEmptyState(
                        context,
                        title = title,
                        message = message,
                        iconRes = R.drawable.wna_search,
                    ),
                )
                emptyHost.visibility = View.VISIBLE
                list.visibility = View.GONE
            } else {
                emptyHost.visibility = View.GONE
                list.visibility = View.VISIBLE
            }
            adapter.submit(visible, state.showCovers)
        }
        applyFilters()
    }
}
