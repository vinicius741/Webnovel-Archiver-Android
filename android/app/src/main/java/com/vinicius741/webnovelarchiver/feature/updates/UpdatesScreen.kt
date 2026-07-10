package com.vinicius741.webnovelarchiver.feature.updates

import android.content.res.ColorStateList
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.feature.library.showLibrary
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.AppBarAction
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.card
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.fullButton
import com.vinicius741.webnovelarchiver.ui.makeEmptyState
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.text
import com.vinicius741.webnovelarchiver.ui.verticalFill
import kotlinx.coroutines.launch

internal fun ScreenHost.showUpdates() {
    activeStory = null
    rerender = { showUpdates() }
    val stories = repository.library()
    val savedIds = repository.getUpdateFollowedStoryIds()
    val followedIds = UpdateTrackerPlanning.normalizeFollowedIds(stories, savedIds)
    if (followedIds != savedIds) scope.launch { repository.saveUpdateFollowedStoryIds(followedIds) }
    val followed = UpdateTrackerPlanning.followedStories(stories, followedIds)
    val syncedIds = updateTrackerScreenState.syncedUpdatedChapterIds
    val storyCount = UpdateTrackerPlanning.updatedStoryCount(followed, syncedIds)
    val chapterCount = UpdateTrackerPlanning.updatedChapterCount(followed, syncedIds)

    screen(
        route = AppRoute.Updates,
        title = "Updates",
        subtitle = "${followed.size} followed · $chapterCount new",
        onBack = { showLibrary() },
        actions = listOf(AppBarAction(R.drawable.wna_check, "Choose novels") { showUpdateFollowSelection() }),
    ) {
        if (stories.isEmpty()) {
            addView(
                makeEmptyState(
                    context,
                    title = "Your library is empty",
                    message = "Import stories before setting up update tracking.",
                    iconRes = R.drawable.wna_menu_book,
                    actionLabel = "Back to Library",
                    onAction = { showLibrary() },
                ),
            )
            return@screen
        }

        lateinit var progressLabel: TextView
        lateinit var progressBar: ProgressBar

        fun refreshProgress() {
            val state = updateTrackerScreenState
            progressLabel.text = state.progressText()
            progressBar.visibility = if (state.syncing) View.VISIBLE else View.GONE
            progressBar.max = state.total.coerceAtLeast(1)
            progressBar.progress = state.completed.coerceAtMost(progressBar.max)
        }
        addView(
            card {
                text("Following ${followed.size} novel${plural(followed.size)}", Type.TITLE_MEDIUM)
                text(
                    "$storyCount novel${plural(storyCount)} with $chapterCount updated chapter${plural(chapterCount)}",
                    Type.BODY_MEDIUM,
                    ThemeManager.colors.onSurfaceVariant,
                )
                progressLabel = makeText(context, "", Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant)
                progressLabel.setPadding(0, dp(Space.SM), 0, 0)
                addView(progressLabel)
                progressBar =
                    ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                        progressTintList = ColorStateList.valueOf(ThemeManager.colors.primary)
                        progressBackgroundTintList = ColorStateList.valueOf(ThemeManager.colors.outlineVariant)
                        layoutParams =
                            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(6)).apply {
                                topMargin = dp(Space.SM)
                            }
                    }
                addView(progressBar)
                fullButton(
                    label = if (updateTrackerScreenState.syncing) "Syncing..." else "Sync Followed Novels",
                    variant = Btn.FILLED,
                    icon = R.drawable.wna_refresh,
                    enabled = followed.isNotEmpty() && !updateTrackerScreenState.syncing,
                    topMarginDp = Space.LG,
                ) { syncFollowedUpdates(::refreshProgress) }
            },
        )
        refreshProgress()
        updateTrackerScreenState.errors.takeIf { it.isNotEmpty() }?.let { errors ->
            addView(
                card {
                    text("${errors.size} sync error${plural(errors.size)}", Type.TITLE_MEDIUM, ThemeManager.colors.error)
                    errors.forEach { (storyId, message) ->
                        val title = stories.firstOrNull { it.id == storyId }?.title ?: storyId
                        text("$title: $message", Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant)
                    }
                },
            )
        }
        when {
            followed.isEmpty() ->
                addView(
                    makeEmptyState(
                        context,
                        title = "Choose novels to follow",
                        message = "Pick the ongoing novels you want to check together.",
                        iconRes = R.drawable.wna_refresh,
                        actionLabel = "Choose novels",
                        onAction = { showUpdateFollowSelection() },
                    ),
                    verticalFill(),
                )
            chapterCount == 0 ->
                addView(
                    makeEmptyState(
                        context,
                        title = "No updated chapters",
                        message = "Sync your followed novels to check whether anything new is available.",
                        iconRes = R.drawable.wna_check,
                    ),
                    verticalFill(),
                )
            else -> {
                val list =
                    RecyclerView(context).apply {
                        layoutManager = LinearLayoutManager(context)
                        adapter = UpdatedItemsAdapter(this@showUpdates).apply { submit(followed, syncedIds) }
                        itemAnimator = null
                        overScrollMode = View.OVER_SCROLL_NEVER
                    }
                addView(list, verticalFill())
            }
        }
    }
}

internal fun plural(count: Int): String = if (count == 1) "" else "s"
