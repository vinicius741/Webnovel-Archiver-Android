package com.vinicius741.webnovelarchiver.feature.downloads

import android.app.AlertDialog
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import com.vinicius741.webnovelarchiver.download.DownloadManagerPlanning
import com.vinicius741.webnovelarchiver.download.GlobalQueueAction
import com.vinicius741.webnovelarchiver.download.QueueAction
import com.vinicius741.webnovelarchiver.download.QueueAppBarPlan
import com.vinicius741.webnovelarchiver.download.QueueSection
import com.vinicius741.webnovelarchiver.download.SourceCheckCard
import com.vinicius741.webnovelarchiver.feature.browser.showBrowser
import com.vinicius741.webnovelarchiver.feature.details.showDetails
import com.vinicius741.webnovelarchiver.feature.settings.resetSourceWebSessions
import com.vinicius741.webnovelarchiver.feature.settings.shareDiagnostics
import com.vinicius741.webnovelarchiver.feature.settings.showDownloadSettings
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.source.SourceRegistry
import com.vinicius741.webnovelarchiver.ui.AppBarAction
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.applyAppTheme
import com.vinicius741.webnovelarchiver.ui.confirm

/**
 * The Downloads screen's app-bar, novel, chapter and site-check menus. Choices are read from
 * [model] at tap time, so a menu never offers an action the queue has already outgrown; every
 * choice runs a model action.
 */
internal class QueueMenus(
    private val host: ScreenHost,
    private val model: QueueScreenModel,
) {
    private val app = host.app

    fun appBarActions(plan: QueueAppBarPlan): List<AppBarAction> =
        buildList {
            when (plan.toggle) {
                GlobalQueueAction.PAUSE_ALL ->
                    add(AppBarAction(R.drawable.wna_pause, "Pause all") { model.run(QueueControl.PauseAll) })
                GlobalQueueAction.RESUME_ALL ->
                    add(AppBarAction(R.drawable.wna_play, "Resume all", ThemeManager.colors.primary) { model.run(QueueControl.ResumeAll) })
                else -> Unit
            }
            add(AppBarAction(R.drawable.wna_tune, "Download settings") { host.showDownloadSettings() })
            if (plan.overflow.isNotEmpty()) add(AppBarAction(R.drawable.wna_more_vert, "More options") { showOverflow() })
        }

    /** Whole-queue actions. */
    private fun showOverflow() {
        val overflow = model.overflow()
        val items =
            buildList {
                if (GlobalQueueAction.RETRY_ALL in overflow) {
                    add(QueueMenuEntry("Retry failed chapters", R.drawable.wna_refresh) { model.run(QueueControl.RetryFailed) })
                }
                if (GlobalQueueAction.CLEAR_FINISHED in overflow) {
                    add(QueueMenuEntry("Clear finished", R.drawable.wna_check) { confirmClearFinished() })
                }
                if (GlobalQueueAction.CANCEL_ALL in overflow) {
                    if (isNotEmpty()) add(null)
                    add(
                        QueueMenuEntry("Cancel all downloads", R.drawable.wna_close, destructive = true) {
                            host.confirm("Cancel all active and queued downloads? Downloaded chapters stay.", confirmLabel = "Cancel all") {
                                model.run(QueueControl.CancelAll)
                            }
                        },
                    )
                }
            }
        if (items.isNotEmpty()) showQueueMenu(app, "Downloads", null, items)
    }

    fun confirmClearFinished() {
        host.confirm("Remove downloaded, failed, and cancelled chapters from the list?", confirmLabel = "Clear") {
            model.run(QueueControl.ClearFinished)
        }
    }

    fun runStoryAction(
        storyId: String,
        action: QueueAction,
    ) {
        when (action) {
            QueueAction.PAUSE -> model.run(QueueControl.PauseStory(storyId))
            QueueAction.RESUME -> model.run(QueueControl.ResumeStory(storyId))
            QueueAction.RETRY -> model.run(QueueControl.RetryStory(storyId))
            QueueAction.CANCEL ->
                host.confirm("Cancel this novel's remaining downloads?", confirmLabel = "Cancel downloads") {
                    model.run(QueueControl.CancelStory(storyId))
                }
            QueueAction.REMOVE -> model.run(QueueControl.RemoveStory(storyId))
        }
    }

    fun showStoryMenu(item: QueueStoryItem) {
        val menu = model.storyMenu(item.storyId)
        val counts = menu.counts
        val items =
            buildList {
                if (item.section == QueueSection.UP_NEXT) {
                    add(QueueMenuEntry("Download now", R.drawable.wna_up) { model.run(QueueControl.DownloadNow(item.storyId)) })
                    if (menu.upNextCount > 1) {
                        add(QueueMenuEntry("Move to bottom", R.drawable.wna_down) { model.run(QueueControl.MoveToBottom(item.storyId)) })
                    }
                }
                if (item.expandable) {
                    add(
                        QueueMenuEntry(if (item.expanded) "Hide chapters" else "Show chapters", R.drawable.wna_list) {
                            model.setStoryExpanded(item.storyId, !item.expanded)
                        },
                    )
                }
                add(QueueMenuEntry("Open novel", R.drawable.wna_menu_book) { host.showDetails(item.storyId) })
                if (counts.hasActive) add(QueueMenuEntry("Pause", R.drawable.wna_pause) { runStoryAction(item.storyId, QueueAction.PAUSE) })
                if (counts.hasPaused) {
                    add(
                        QueueMenuEntry("Resume", R.drawable.wna_play) { runStoryAction(item.storyId, QueueAction.RESUME) },
                    )
                }
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
        showQueueMenu(app, item.title, "${counts.completed} of ${counts.total} chapters", items)
    }

    fun showChapterMenu(item: QueueChapterItem) {
        val job = model.job(item.jobId) ?: return
        val items =
            buildList {
                DownloadManagerPlanning.chapterActions(job.status).forEach { action ->
                    when (action) {
                        QueueAction.PAUSE ->
                            add(QueueMenuEntry("Pause", R.drawable.wna_pause) { model.run(QueueControl.PauseJob(job.id)) })
                        QueueAction.RESUME ->
                            add(QueueMenuEntry("Resume", R.drawable.wna_play) { model.run(QueueControl.ResumeJob(job.id)) })
                        QueueAction.RETRY ->
                            if (item.blocked) {
                                model.providerOf(job)?.let { providerId ->
                                    add(
                                        QueueMenuEntry("Verify ${siteName(providerId)}", R.drawable.wna_shield) {
                                            model.verifySite(providerId, job.chapter.url)
                                        },
                                    )
                                }
                            } else {
                                add(QueueMenuEntry("Retry", R.drawable.wna_refresh) { model.run(QueueControl.RetryJob(job.id)) })
                            }
                        QueueAction.CANCEL ->
                            add(
                                QueueMenuEntry("Cancel", R.drawable.wna_close, destructive = true) {
                                    model.run(QueueControl.CancelJob(job.id))
                                },
                            )
                        QueueAction.REMOVE ->
                            add(QueueMenuEntry("Remove from list", R.drawable.wna_delete) { model.run(QueueControl.RemoveJob(job.id)) })
                    }
                }
                if (job.error != null) add(QueueMenuEntry("Error details", R.drawable.wna_info) { showChapterErrorDetails(job) })
            }
        if (items.isNotEmpty()) showQueueMenu(app, item.title, item.status, items)
    }

    /** Fallbacks when the in-app check keeps failing, kept off the card so it stays one decision. */
    fun showSourceCheckTrouble(card: SourceCheckCard) {
        val name = siteName(card.providerId)
        val provider = SourceRegistry.getById(card.providerId)
        val url = card.sampleUrl ?: provider?.baseUrl
        val items =
            buildList {
                url?.let { add(QueueMenuEntry("Open $name in your browser", R.drawable.wna_open_external) { host.showBrowser(it) }) }
                provider?.let {
                    add(
                        QueueMenuEntry("Reset $name web session", R.drawable.wna_restore) {
                            host.confirm("Reset the $name web session? You'll need to verify again afterwards.", confirmLabel = "Reset") {
                                host.resetSourceWebSessions(listOf(it))
                            }
                        },
                    )
                }
                add(QueueMenuEntry("Share diagnostics", R.drawable.wna_bug_report) { host.shareDiagnostics() })
                add(null)
                add(QueueMenuEntry("Pause $name downloads", R.drawable.wna_pause) { model.run(QueueControl.PauseSource(card.providerId)) })
            }
        showQueueMenu(app, "Having trouble?", "Some sites ask for a quick human check before chapters can download.", items)
    }

    private fun showChapterErrorDetails(job: DownloadJob) {
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
}
