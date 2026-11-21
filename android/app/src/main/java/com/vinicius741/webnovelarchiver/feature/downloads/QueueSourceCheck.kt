package com.vinicius741.webnovelarchiver.feature.downloads

import com.vinicius741.webnovelarchiver.app.appContainer
import com.vinicius741.webnovelarchiver.download.DownloadQueueSectionPlanning
import com.vinicius741.webnovelarchiver.feature.browser.CloudflareSolveActivity
import com.vinicius741.webnovelarchiver.feature.browser.SourceAccessRetryCoordinator
import com.vinicius741.webnovelarchiver.feature.browser.SourceCheckOutcomes
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.source.SourceRegistry

/**
 * Opens the site check straight from a card. The solver resumes the site's chapters itself once it
 * passes; the armed retry only makes sure the download service is running when the app returns.
 */
internal fun ScreenHost.startSourceCheck(
    providerId: String,
    url: String,
) {
    SourceCheckOutcomes.launched(providerId)
    val token = SourceAccessRetryCoordinator.arm { startDownloadForegroundService() }
    CloudflareSolveActivity.launch(app, url, token)
}

/** A blocked site that holds this novel's chapters, for the novel page's banner. */
internal data class StorySourceCheck(
    val providerId: String,
    val siteName: String,
    val sampleUrl: String,
)

internal fun ScreenHost.storySourceCheck(storyId: String): StorySourceCheck? {
    val site =
        DownloadQueueSectionPlanning
            .blockedSites(
                jobs = repository.downloadState.value.queue,
                providerIdForJob = { SourceRegistry.getProvider(it.sourceId, it.chapter.url)?.id },
                isSourceBlocked = app.appContainer.network::isSourceBlocked,
            ).firstOrNull { storyId in it.storyIds } ?: return null
    return StorySourceCheck(site.providerId, siteName(site.providerId), site.sampleUrl)
}
