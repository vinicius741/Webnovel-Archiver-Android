package com.vinicius741.webnovelarchiver.feature.updates

import com.vinicius741.webnovelarchiver.data.repository.DownloadUiSnapshot
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.story.UpdatesPlanning.Overview
import com.vinicius741.webnovelarchiver.feature.browser.showSourceAccessBlockedDialog
import com.vinicius741.webnovelarchiver.feature.story.handleManualSyncDownloads
import com.vinicius741.webnovelarchiver.feature.story.queueDownload
import com.vinicius741.webnovelarchiver.feature.story.storyWork
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.operations.ManualStorySyncResult
import com.vinicius741.webnovelarchiver.operations.SyncDownloadPlan
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.flow.StateFlow

/** [UpdatesSyncHost] over the activity: the shared story work, the published library, and the activity's dialogs. */
internal class HostUpdatesSyncHost(
    private val host: ScreenHost,
) : UpdatesSyncHost {
    override suspend fun sync(
        story: Story,
        inBatch: Boolean,
    ): ManualStorySyncResult = host.storyWork.syncFromUpdates(story, inBatch)

    override fun story(id: String): Story? = host.repository.story(id)

    override fun handleDownloads(
        story: Story,
        plan: SyncDownloadPlan,
    ) = host.handleManualSyncDownloads(story, plan)

    override fun showBlocked(story: Story) =
        host.showSourceAccessBlockedDialog(story.sourceUrl) { host.repository.story(story.id)?.let(host.updatesSyncs::sync) }

    override fun toast(message: String) = host.toast(message)

    override fun onUpdates(): Boolean = host.navigator.current == AppRoute.Updates

    override fun canShow(): Boolean = !host.app.isFinishing && !host.app.isDestroyed
}

/** [UpdatesData] over the activity's repository, its Updates syncs and forecast cache, and its download start. */
internal class HostUpdatesData(
    private val host: ScreenHost,
) : UpdatesData {
    private val holder get() = host.updatesSyncs

    override val downloads: StateFlow<DownloadUiSnapshot> get() = host.repository.downloadState

    override val syncs: StateFlow<UpdatesSyncsState> get() = host.updatesSyncs.state

    override val syncing: StateFlow<Map<String, String>> get() = host.operations.updatesSyncs

    override fun now(): Long = System.currentTimeMillis()

    override fun plannedOverview(library: List<Story>): Overview? = holder.overviewCache.latest(library)

    override fun overview(
        library: List<Story>,
        now: Long,
    ): Overview = holder.overviewCache.overview(library, now)

    override fun story(id: String): Story? = host.repository.story(id)

    override fun sync(story: Story) = holder.sync(story)

    override fun syncAll(storyIds: List<String>) = holder.syncAll(storyIds)

    override fun stopBatch() = holder.stop()

    override fun showPendingReviews() = holder.showPendingReviews()

    override fun queueDownload(
        story: Story,
        indexes: List<Int>,
    ) = host.queueDownload(story, indexes)
}
