package com.vinicius741.webnovelarchiver.feature.downloads

import com.vinicius741.webnovelarchiver.app.appContainer
import com.vinicius741.webnovelarchiver.download.DownloadQueueSectionPlanning
import com.vinicius741.webnovelarchiver.download.SourceCheckCard
import com.vinicius741.webnovelarchiver.feature.browser.CloudflareSolveActivity
import com.vinicius741.webnovelarchiver.feature.browser.SourceAccessRetryCoordinator
import com.vinicius741.webnovelarchiver.feature.browser.SourceCheckOutcomes
import com.vinicius741.webnovelarchiver.feature.browser.showBrowser
import com.vinicius741.webnovelarchiver.feature.settings.resetSourceWebSessions
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.source.SourceRegistry
import com.vinicius741.webnovelarchiver.ui.OptionsDialogItem
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.showStyledOptionsDialog

/**
 * Opens the site check straight from a card. The solver resumes the site's chapters itself once it
 * passes; the armed retry only makes sure the download service is running when the app returns.
 */
internal fun ScreenHost.startSourceCheck(
    providerId: String,
    url: String,
) {
    SourceCheckOutcomes.launched(providerId)
    SourceAccessRetryCoordinator.arm { startDownloadForegroundService() }
    CloudflareSolveActivity.launch(app, url)
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
            url?.let { add(OptionsDialogItem.Option("Open $name in your browser") { showBrowser(it) }) }
            provider?.let {
                add(
                    OptionsDialogItem.Option("Reset $name web session") {
                        confirm("Reset the $name web session? You'll need to verify again afterwards.", confirmLabel = "Reset") {
                            resetSourceWebSessions(listOf(it))
                        }
                    },
                )
            }
            add(OptionsDialogItem.Divider)
            add(
                OptionsDialogItem.Option("Pause $name downloads") {
                    launchQueueControl("pauseSource") { downloadEngine.pauseSource(card.providerId) }
                },
            )
        }
    showStyledOptionsDialog("Trouble verifying $name?", items)
}
