package com.vinicius741.webnovelarchiver.feature.downloads

import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import com.vinicius741.webnovelarchiver.download.DownloadCounts
import com.vinicius741.webnovelarchiver.download.DownloadManagerPlanning
import com.vinicius741.webnovelarchiver.download.GlobalQueueAction
import com.vinicius741.webnovelarchiver.download.QueueAction
import com.vinicius741.webnovelarchiver.download.QueueAppBarPlan
import com.vinicius741.webnovelarchiver.download.QueueSection
import com.vinicius741.webnovelarchiver.feature.details.showDetails
import com.vinicius741.webnovelarchiver.feature.settings.showDownloadSettings
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.AppBarAction
import com.vinicius741.webnovelarchiver.ui.BottomSheetHandle
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.applyAppTheme
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeSheetRow
import com.vinicius741.webnovelarchiver.ui.showBottomSheet

// App-bar, novel, and chapter menus for the Downloads screen. Choices are computed at tap time
// from the live queue, so a menu never offers an action the queue has already outgrown.

internal fun ScreenHost.queueAppBarActions(plan: QueueAppBarPlan): List<AppBarAction> =
    buildList {
        when (plan.toggle) {
            GlobalQueueAction.PAUSE_ALL ->
                add(
                    AppBarAction(R.drawable.wna_pause, "Pause all") {
                        launchQueueControl("pauseAll") { downloadEngine.pauseAll() }
                    },
                )
            GlobalQueueAction.RESUME_ALL ->
                add(
                    AppBarAction(R.drawable.wna_play, "Resume all", ThemeManager.colors.primary) {
                        launchQueueControl("resumeAll") { downloadEngine.resumeAll() }
                        startDownloadForegroundService()
                    },
                )
            else -> Unit
        }
        add(AppBarAction(R.drawable.wna_tune, "Download settings") { showDownloadSettings() })
        if (plan.overflow.isNotEmpty()) add(AppBarAction(R.drawable.wna_more_vert, "More options") { showQueueOverflow() })
    }

/** Whole-queue actions, computed at tap time so the menu never shows a stale choice. */
private fun ScreenHost.showQueueOverflow() {
    val plan = queuePlan(repository.downloadState.value.queue)
    val overflow = DownloadManagerPlanning.appBar(plan.counts, plan.failedChapters).overflow
    val items =
        buildList {
            if (GlobalQueueAction.RETRY_ALL in overflow) {
                add(QueueMenuEntry("Retry failed chapters", R.drawable.wna_refresh) { retryFailedChapters() })
            }
            if (GlobalQueueAction.CLEAR_FINISHED in overflow) {
                add(QueueMenuEntry("Clear finished", R.drawable.wna_check) { confirmClearFinished() })
            }
            if (GlobalQueueAction.CANCEL_ALL in overflow) {
                if (isNotEmpty()) add(null)
                add(
                    QueueMenuEntry("Cancel all downloads", R.drawable.wna_close, destructive = true) {
                        confirm("Cancel all active and queued downloads? Downloaded chapters stay.", confirmLabel = "Cancel all") {
                            launchQueueControl("cancelAll") { downloadEngine.cancelAll() }
                        }
                    },
                )
            }
        }
    if (items.isNotEmpty()) showQueueMenu("Downloads", null, items)
}

internal fun ScreenHost.retryFailedChapters() {
    launchQueueControl("retryFailed") { downloadEngine.retryFailed() }
    startDownloadForegroundService()
}

internal fun ScreenHost.confirmClearFinished() {
    confirm("Remove downloaded, failed, and cancelled chapters from the list?", confirmLabel = "Clear") {
        launchQueueControl("clearFinished") { downloadEngine.clearFinished() }
    }
}

private fun ScreenHost.storyJobs(storyId: String): List<DownloadJob> =
    repository.downloadState.value.queue
        .filter { it.storyId == storyId }

internal fun ScreenHost.runStoryAction(
    storyId: String,
    action: QueueAction,
) {
    when (action) {
        QueueAction.PAUSE -> launchQueueControl("pauseStory") { downloadEngine.pauseStory(storyId) }
        QueueAction.RESUME -> {
            launchQueueControl("resumeStory") { downloadEngine.resumeStory(storyId) }
            startDownloadForegroundService()
        }
        QueueAction.RETRY -> {
            launchQueueControl("retryFailedForStory") { downloadEngine.retryFailedForStory(storyId) }
            startDownloadForegroundService()
        }
        QueueAction.CANCEL ->
            confirm("Cancel this novel's remaining downloads?", confirmLabel = "Cancel downloads") {
                launchQueueControl("cancelStory") { downloadEngine.cancelStory(storyId) }
            }
        QueueAction.REMOVE -> launchQueueControl("removeStory") { downloadEngine.removeStory(storyId) }
    }
}

internal fun ScreenHost.showStoryMenu(
    item: QueueStoryItem,
    renderNow: () -> Unit,
) {
    val counts = DownloadCounts.from(storyJobs(item.storyId))
    val upNextCount = queuePlan(repository.downloadState.value.queue).upNext.size
    val items =
        buildList {
            if (item.section == QueueSection.UP_NEXT) {
                add(
                    QueueMenuEntry("Download next", R.drawable.wna_up) {
                        launchQueueControl("downloadStoryNext") { downloadEngine.downloadStoryNext(item.storyId) }
                    },
                )
                if (upNextCount > 1) {
                    add(
                        QueueMenuEntry("Move to bottom", R.drawable.wna_down) {
                            launchQueueControl("moveStoryToEnd") { downloadEngine.moveStoryToEnd(item.storyId) }
                        },
                    )
                }
            }
            if (item.expandable) {
                add(
                    QueueMenuEntry(if (item.expanded) "Hide chapters" else "Show chapters", R.drawable.wna_list) {
                        storyExpandOverride[item.storyId] = !item.expanded
                        renderNow()
                    },
                )
            }
            add(QueueMenuEntry("Open novel", R.drawable.wna_menu_book) { showDetails(item.storyId) })
            if (counts.hasActive) add(QueueMenuEntry("Pause", R.drawable.wna_pause) { runStoryAction(item.storyId, QueueAction.PAUSE) })
            if (counts.hasPaused) add(QueueMenuEntry("Resume", R.drawable.wna_play) { runStoryAction(item.storyId, QueueAction.RESUME) })
            if (counts.hasFailed || counts.hasCancelled) {
                add(QueueMenuEntry("Retry failed chapters", R.drawable.wna_refresh) { runStoryAction(item.storyId, QueueAction.RETRY) })
            }
            add(null)
            if (counts.hasActive || counts.hasPaused) {
                add(
                    QueueMenuEntry("Cancel downloads", R.drawable.wna_close, destructive = true) {
                        runStoryAction(item.storyId, QueueAction.CANCEL)
                    },
                )
            } else {
                add(QueueMenuEntry("Remove from list", R.drawable.wna_delete) { runStoryAction(item.storyId, QueueAction.REMOVE) })
            }
        }
    showQueueMenu(item.title, "${counts.completed} of ${counts.total} chapters", items)
}

internal fun ScreenHost.showChapterMenu(item: QueueChapterItem) {
    val job =
        repository.downloadState.value.queue
            .firstOrNull { it.id == item.jobId } ?: return
    val items =
        buildList {
            DownloadManagerPlanning.chapterActions(job.status).forEach { action ->
                when (action) {
                    QueueAction.PAUSE ->
                        add(
                            QueueMenuEntry(
                                "Pause",
                                R.drawable.wna_pause,
                            ) { launchQueueControl("pauseJob") { downloadEngine.pauseJob(job.id) } },
                        )
                    QueueAction.RESUME ->
                        add(
                            QueueMenuEntry("Resume", R.drawable.wna_play) {
                                launchQueueControl("resumeJob") { downloadEngine.resumeJob(job.id) }
                                startDownloadForegroundService()
                            },
                        )
                    QueueAction.RETRY ->
                        if (item.blocked) {
                            providerIdForJob(job)?.let { providerId ->
                                add(
                                    QueueMenuEntry("Verify ${siteName(providerId)}", R.drawable.wna_shield) {
                                        startSourceCheck(providerId, job.chapter.url)
                                    },
                                )
                            }
                        } else {
                            add(
                                QueueMenuEntry("Retry", R.drawable.wna_refresh) {
                                    launchQueueControl("retryJob") { downloadEngine.retryJob(job.id) }
                                    startDownloadForegroundService()
                                },
                            )
                        }
                    QueueAction.CANCEL ->
                        add(
                            QueueMenuEntry("Cancel", R.drawable.wna_close, destructive = true) {
                                launchQueueControl("cancelJob") { downloadEngine.cancelJob(job.id) }
                            },
                        )
                    QueueAction.REMOVE ->
                        add(
                            QueueMenuEntry("Remove from list", R.drawable.wna_delete) {
                                launchQueueControl("removeJob") { downloadEngine.removeJob(job.id) }
                            },
                        )
                }
            }
            if (job.error != null) add(QueueMenuEntry("Error details", R.drawable.wna_info) { showChapterErrorDetails(job) })
        }
    if (items.isNotEmpty()) showQueueMenu(item.title, item.status, items)
}

private fun ScreenHost.showChapterErrorDetails(job: DownloadJob) {
    val message =
        buildString {
            append(job.error.orEmpty())
            job.errorCode?.let { append("\n\nCode: ").append(it) }
            job.errorCategory?.let { append("\nCategory: ").append(it) }
            append("\nRetries: ${job.retryCount} of ${job.maxRetries}")
        }
    AlertDialog
        .Builder(app)
        .setTitle("Chapter ${job.chapterIndex + 1}")
        .setMessage(message)
        .setPositiveButton("OK", null)
        .show()
        .also { it.applyAppTheme() }
}

/** Patches the app bar's subtitle in place, so a queue tick never rebuilds the screen for it. */
internal fun ScreenHost.setQueueSubtitle(text: String) {
    val title = frame.findViewWithTag<TextView>(APP_BAR_TITLE_TAG) ?: return
    val subtitle = (title.parent as? LinearLayout)?.getChildAt(1) as? TextView ?: return
    if (subtitle.text != text) subtitle.text = text
}

// The scaffold tags its app-bar title with this; the subtitle is the next view in that column.
private const val APP_BAR_TITLE_TAG = "wna_app_bar_title"
