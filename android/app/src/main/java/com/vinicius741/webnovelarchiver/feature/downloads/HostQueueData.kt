package com.vinicius741.webnovelarchiver.feature.downloads

import com.vinicius741.webnovelarchiver.app.appContainer
import com.vinicius741.webnovelarchiver.data.repository.DownloadUiSnapshot
import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import com.vinicius741.webnovelarchiver.download.DownloadForegroundService
import com.vinicius741.webnovelarchiver.download.DownloadPacingSnapshot
import com.vinicius741.webnovelarchiver.download.SourceCheckOutcome
import com.vinicius741.webnovelarchiver.feature.browser.SourceCheckOutcomes
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.source.SourceRegistry
import com.vinicius741.webnovelarchiver.ui.tickerFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** [QueueData] over the activity's repository, download engine and service, request pacer and site checks. */
internal class HostQueueData(
    private val host: ScreenHost,
) : QueueData {
    private val container get() = host.app.appContainer
    private val engine get() = host.downloadEngine

    override val downloads: StateFlow<DownloadUiSnapshot> get() = host.repository.downloadState

    override val pacing: StateFlow<Map<String, DownloadPacingSnapshot>> get() = container.downloadPacer.snapshots

    override val checkOutcomes: StateFlow<Map<String, SourceCheckOutcome>> get() = SourceCheckOutcomes.state

    override fun nowMillis(): Long = container.time.nowMillis()

    override fun ticker(): Flow<Long> = tickerFlow(nowMillis = container.time::nowMillis)

    override fun providerIdForJob(job: DownloadJob): String? = SourceRegistry.getProvider(job.sourceId, job.chapter.url)?.id

    override fun isSourceBlocked(providerId: String): Boolean = container.network.isSourceBlocked(providerId)

    override suspend fun apply(control: QueueControl) {
        when (control) {
            QueueControl.PauseAll -> engine.pauseAll()
            QueueControl.ResumeAll -> engine.resumeAll()
            QueueControl.RetryFailed -> engine.retryFailed()
            QueueControl.ClearFinished -> engine.clearFinished()
            QueueControl.CancelAll -> engine.cancelAll()
            is QueueControl.PauseStory -> engine.pauseStory(control.storyId)
            is QueueControl.ResumeStory -> engine.resumeStory(control.storyId)
            is QueueControl.RetryStory -> engine.retryFailedForStory(control.storyId)
            is QueueControl.CancelStory -> engine.cancelStory(control.storyId)
            is QueueControl.RemoveStory -> engine.removeStory(control.storyId)
            is QueueControl.DownloadNow -> engine.downloadStoryNext(control.storyId)
            is QueueControl.MoveToBottom -> engine.moveStoryToEnd(control.storyId)
            is QueueControl.Reorder -> engine.reorderStories(control.storyIds)
            is QueueControl.PauseJob -> engine.pauseJob(control.jobId)
            is QueueControl.ResumeJob -> engine.resumeJob(control.jobId)
            is QueueControl.RetryJob -> engine.retryJob(control.jobId)
            is QueueControl.CancelJob -> engine.cancelJob(control.jobId)
            is QueueControl.RemoveJob -> engine.removeJob(control.jobId)
            is QueueControl.PauseSource -> engine.pauseSource(control.providerId)
        }
    }

    override fun startDownloads() = host.startDownloadForegroundService()

    override fun startSourceCheck(
        providerId: String,
        url: String,
    ) = host.startSourceCheck(providerId, url)
}

/** Starts the download service, asking for the notification permission first when needed. */
internal fun ScreenHost.startDownloadForegroundService() {
    requestNotificationPermissionForDownload()
    DownloadForegroundService.start(app)
}
