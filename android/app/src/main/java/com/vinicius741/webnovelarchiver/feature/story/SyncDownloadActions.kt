package com.vinicius741.webnovelarchiver.feature.story

import android.app.AlertDialog
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.feature.details.showChapterSelection
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.applyAppTheme
import com.vinicius741.webnovelarchiver.ui.confirm

internal fun ScreenHost.handleManualSyncDownloads(
    story: Story,
    plan: SyncDownloadPlan,
) {
    when (plan.action) {
        SyncDownloadAction.NONE -> Unit
        SyncDownloadAction.AUTO_QUEUE -> queueDownload(story, plan.chapterIndexes)
        SyncDownloadAction.REVIEW -> showLargeSyncDownloadDialog(story, plan)
    }
}

private fun ScreenHost.showLargeSyncDownloadDialog(
    story: Story,
    plan: SyncDownloadPlan,
) {
    val count = plan.chapterIndexes.size
    val automaticCount = minOf(count, SyncDownloadPlanning.AUTO_DOWNLOAD_LIMIT)
    val choices =
        arrayOf(
            "Download first $automaticCount",
            "Choose chapters",
            "Download all $count",
        )
    AlertDialog
        .Builder(app)
        .setTitle("$count new chapters found")
        .setMessage(
            "This is a large update. Choose how much to download now; chapters you leave out will remain available for later.",
        ).setItems(choices) { _, choice ->
            when (choice) {
                0 -> queueDownload(story, plan.chapterIndexes.take(automaticCount))
                1 -> showChapterSelection(story.id)
                2 ->
                    confirm(
                        "Queue all $count new chapters? This may take a long time and use significant data and storage.",
                        confirmLabel = "Download All",
                    ) {
                        queueDownload(story, plan.chapterIndexes)
                    }
            }
        }.setNegativeButton("Sync only", null)
        .show()
        .applyAppTheme()
}
