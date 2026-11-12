package com.vinicius741.webnovelarchiver.feature.downloads

import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.app.appContainer
import com.vinicius741.webnovelarchiver.data.diagnostics.BypassLogExporter
import com.vinicius741.webnovelarchiver.download.DownloadQueueSectionPlanning
import com.vinicius741.webnovelarchiver.download.SourceCheckCard
import com.vinicius741.webnovelarchiver.feature.browser.CloudflareSolveActivity
import com.vinicius741.webnovelarchiver.feature.browser.SourceAccessRetryCoordinator
import com.vinicius741.webnovelarchiver.feature.browser.SourceCheckOutcomes
import com.vinicius741.webnovelarchiver.feature.browser.showBrowser
import com.vinicius741.webnovelarchiver.feature.settings.resetSourceWebSessions
import com.vinicius741.webnovelarchiver.feature.story.exportAndShare
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.source.SourceRegistry
import com.vinicius741.webnovelarchiver.ui.confirm

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

/** Fallbacks when the in-app check keeps failing, kept off the card so it stays one decision. */
internal fun ScreenHost.showSourceCheckTrouble(card: SourceCheckCard) {
    val name = siteName(card.providerId)
    val provider = SourceRegistry.getById(card.providerId)
    val url = card.sampleUrl ?: provider?.baseUrl
    val items =
        buildList {
            url?.let { add(QueueMenuEntry("Open $name in your browser", R.drawable.wna_open_external) { showBrowser(it) }) }
            provider?.let {
                add(
                    QueueMenuEntry("Reset $name web session", R.drawable.wna_restore) {
                        confirm("Reset the $name web session? You'll need to verify again afterwards.", confirmLabel = "Reset") {
                            resetSourceWebSessions(listOf(it))
                        }
                    },
                )
            }
            add(
                QueueMenuEntry("Share source access logs", R.drawable.wna_bug_report) {
                    val network = app.appContainer.network
                    exportAndShare(
                        { BypassLogExporter.export(app.appContainer.repository.storage.backupRoot, network, repository.queue()) },
                    )
                },
            )
            add(null)
            add(
                QueueMenuEntry("Pause $name downloads", R.drawable.wna_pause) {
                    launchQueueControl("pauseSource") { downloadEngine.pauseSource(card.providerId) }
                },
            )
        }
    showQueueMenu("Having trouble?", "Some sites ask for a quick human check before chapters can download.", items)
}
