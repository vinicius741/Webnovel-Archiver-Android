package com.vinicius741.webnovelarchiver.feature.downloads

import android.app.AlertDialog
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
import com.vinicius741.webnovelarchiver.ui.OptionsDialogItem
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.applyAppTheme
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.showStyledOptionsDialog

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
        add(AppBarAction(R.drawable.wna_settings, "Download Settings") { showDownloadSettings() })
        if (plan.overflow.isNotEmpty()) add(AppBarAction(R.drawable.wna_more_vert, "More options") { showQueueOverflow() })
    }

/** Whole-queue actions, computed at tap time so the menu never shows a stale choice. */
private fun ScreenHost.showQueueOverflow() {
    val plan = queuePlan(repository.downloadState.value.queue)
    val overflow = DownloadManagerPlanning.appBar(plan.counts, plan.failedChapters).overflow
    val items =
        buildList {
            if (GlobalQueueAction.RETRY_ALL in overflow) add(OptionsDialogItem.Option("Retry failed chapters") { retryFailedChapters() })
            if (GlobalQueueAction.CLEAR_FINISHED in overflow) add(OptionsDialogItem.Option("Clear finished") { confirmClearFinished() })
            if (GlobalQueueAction.CANCEL_ALL in overflow) {
                add(OptionsDialogItem.Divider)
                add(
                    OptionsDialogItem.Option("Cancel all downloads", destructive = true) {
                        confirm("Cancel all active and queued downloads?", confirmLabel = "Cancel all") {
                            launchQueueControl("cancelAll") { downloadEngine.cancelAll() }
                        }
                    },
                )
            }
        }
    if (items.isNotEmpty()) showStyledOptionsDialog("Downloads", items)
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
                    OptionsDialogItem.Option("Download next") {
                        launchQueueControl("downloadStoryNext") { downloadEngine.downloadStoryNext(item.storyId) }
                    },
                )
                if (upNextCount > 1) {
                    add(
                        OptionsDialogItem.Option("Move to bottom") {
                            launchQueueControl("moveStoryToEnd") { downloadEngine.moveStoryToEnd(item.storyId) }
                        },
                    )
                }
            }
            if (item.expandable) {
                add(
                    OptionsDialogItem.Option(if (item.expanded) "Hide chapters" else "Show chapters") {
                        storyExpandOverride[item.storyId] = !item.expanded
                        renderNow()
                    },
                )
            }
            add(OptionsDialogItem.Option("Open novel") { showDetails(item.storyId) })
            if (counts.hasActive) add(OptionsDialogItem.Option("Pause") { runStoryAction(item.storyId, QueueAction.PAUSE) })
            if (counts.hasPaused) add(OptionsDialogItem.Option("Resume") { runStoryAction(item.storyId, QueueAction.RESUME) })
            if (counts.hasFailed || counts.hasCancelled) {
                add(OptionsDialogItem.Option("Retry failed chapters") { runStoryAction(item.storyId, QueueAction.RETRY) })
            }
            add(OptionsDialogItem.Divider)
            if (counts.hasActive || counts.hasPaused) {
                add(OptionsDialogItem.Option("Cancel downloads", destructive = true) { runStoryAction(item.storyId, QueueAction.CANCEL) })
            } else {
                add(OptionsDialogItem.Option("Remove from list") { runStoryAction(item.storyId, QueueAction.REMOVE) })
            }
        }
    showStyledOptionsDialog(item.title, items)
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
                        add(OptionsDialogItem.Option("Pause") { launchQueueControl("pauseJob") { downloadEngine.pauseJob(job.id) } })
                    QueueAction.RESUME ->
                        add(
                            OptionsDialogItem.Option("Resume") {
                                launchQueueControl("resumeJob") { downloadEngine.resumeJob(job.id) }
                                startDownloadForegroundService()
                            },
                        )
                    QueueAction.RETRY ->
                        if (item.blocked) {
                            providerIdForJob(job)?.let { providerId ->
                                add(
                                    OptionsDialogItem.Option(
                                        "Verify ${siteName(providerId)}",
                                    ) { startSourceCheck(providerId, job.chapter.url) },
                                )
                            }
                        } else {
                            add(
                                OptionsDialogItem.Option("Retry") {
                                    launchQueueControl("retryJob") { downloadEngine.retryJob(job.id) }
                                    startDownloadForegroundService()
                                },
                            )
                        }
                    QueueAction.CANCEL ->
                        add(
                            OptionsDialogItem.Option("Cancel", destructive = true) {
                                launchQueueControl("cancelJob") { downloadEngine.cancelJob(job.id) }
                            },
                        )
                    QueueAction.REMOVE ->
                        add(
                            OptionsDialogItem.Option(
                                "Remove from list",
                            ) { launchQueueControl("removeJob") { downloadEngine.removeJob(job.id) } },
                        )
                }
            }
            if (job.error != null) add(OptionsDialogItem.Option("Error details") { showChapterErrorDetails(job) })
        }
    if (items.isNotEmpty()) showStyledOptionsDialog(item.title, items)
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
