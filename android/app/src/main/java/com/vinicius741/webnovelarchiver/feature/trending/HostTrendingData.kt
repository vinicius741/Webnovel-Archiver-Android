package com.vinicius741.webnovelarchiver.feature.trending

import com.vinicius741.webnovelarchiver.data.repository.DownloadUiSnapshot
import com.vinicius741.webnovelarchiver.domain.model.StoryMetricHistory
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import kotlinx.coroutines.flow.StateFlow

/** [TrendingData] over the activity's repository. */
internal class HostTrendingData(
    private val host: ScreenHost,
) : TrendingData {
    override val downloads: StateFlow<DownloadUiSnapshot> get() = host.repository.downloadState

    override suspend fun history(storyId: String): StoryMetricHistory = host.repository.getMetricHistory(storyId)

    override fun now(): Long = System.currentTimeMillis()
}
