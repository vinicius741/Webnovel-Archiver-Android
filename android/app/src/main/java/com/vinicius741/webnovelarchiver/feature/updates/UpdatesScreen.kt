package com.vinicius741.webnovelarchiver.feature.updates

import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.story.StoryActionGuards
import com.vinicius741.webnovelarchiver.feature.library.showLibrary
import com.vinicius741.webnovelarchiver.feature.story.SyncDownloadPlanning
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.AppBarAction
import com.vinicius741.webnovelarchiver.ui.makeEmptyState
import com.vinicius741.webnovelarchiver.ui.screen
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
    val syncableFollowed = followed.filter(StoryActionGuards::canAutoSync)
    val unavailableCount = followed.size - syncableFollowed.size
    val syncedIds = updateTrackerScreenState.syncedUpdatedChapterIds
    val storyCount = UpdateTrackerPlanning.updatedStoryCount(followed, syncedIds)
    val chapterCount = UpdateTrackerPlanning.updatedChapterCount(followed, syncedIds)
    val updatesRequiringReview = syncedIds.values.filter { it.size > SyncDownloadPlanning.AUTO_DOWNLOAD_LIMIT }
    val reviewStoryCount = updatesRequiringReview.size
    val reviewChapterCount = updatesRequiringReview.sumOf { it.size }

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

        val refreshProgress =
            addUpdatesSummaryCard(
                state = updateTrackerScreenState,
                counts =
                    UpdatesSummaryCounts(
                        followedCount = followed.size,
                        storyCount = storyCount,
                        chapterCount = chapterCount,
                        reviewStoryCount = reviewStoryCount,
                        reviewChapterCount = reviewChapterCount,
                        unavailableCount = unavailableCount,
                    ),
                canSync = syncableFollowed.isNotEmpty(),
                onSync = { onProgress -> syncFollowedUpdates(onProgress) },
            )
        refreshProgress()
        buildUpdateSyncErrors(updateTrackerScreenState.errors, stories)?.let(::addView)
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
