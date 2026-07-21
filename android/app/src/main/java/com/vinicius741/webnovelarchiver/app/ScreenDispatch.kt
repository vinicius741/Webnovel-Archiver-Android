package com.vinicius741.webnovelarchiver.app

import com.vinicius741.webnovelarchiver.feature.cleanup.showCleanupRules
import com.vinicius741.webnovelarchiver.feature.details.showChapterSelection
import com.vinicius741.webnovelarchiver.feature.details.showDetails
import com.vinicius741.webnovelarchiver.feature.details.showLegacyEpubs
import com.vinicius741.webnovelarchiver.feature.details.showTrends
import com.vinicius741.webnovelarchiver.feature.downloads.showQueue
import com.vinicius741.webnovelarchiver.feature.library.showAddStory
import com.vinicius741.webnovelarchiver.feature.library.showLibrary
import com.vinicius741.webnovelarchiver.feature.library.showLibrarySelection
import com.vinicius741.webnovelarchiver.feature.reader.showReader
import com.vinicius741.webnovelarchiver.feature.settings.showDataBackup
import com.vinicius741.webnovelarchiver.feature.settings.showDownloadSettings
import com.vinicius741.webnovelarchiver.feature.settings.showNotifications
import com.vinicius741.webnovelarchiver.feature.settings.showSettings
import com.vinicius741.webnovelarchiver.feature.settings.showTabs
import com.vinicius741.webnovelarchiver.feature.settings.showTtsSettings
import com.vinicius741.webnovelarchiver.feature.updates.showUpdateFollowSelection
import com.vinicius741.webnovelarchiver.feature.updates.showUpdates
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost

/**
 * Maps an [AppRoute] to its `showXxx()` renderer. Pure delegation — reads no host state — so it
 * lives apart from [MainActivity]'s lifecycle wiring. [MainActivity.renderRoute] is a one-line
 * delegate to this extension.
 */
internal fun ScreenHost.renderRouteDispatch(route: AppRoute) {
    when (route) {
        AppRoute.Library -> showLibrary()
        AppRoute.AddStory -> showAddStory()
        is AppRoute.LibrarySelection -> showLibrarySelection(route.selectedStoryIds)
        is AppRoute.Details -> showDetails(route.storyId)
        is AppRoute.ChapterSelection -> showChapterSelection(route.storyId, route.selectedChapterIds)
        is AppRoute.LegacyEpubs -> showLegacyEpubs(route.storyId)
        is AppRoute.Trends -> showTrends(route.storyId, route.focus)
        is AppRoute.Reader -> showReader(route.storyId, route.chapterId)
        AppRoute.Queue -> showQueue()
        AppRoute.Updates -> showUpdates()
        AppRoute.UpdateFollowSelection -> showUpdateFollowSelection()
        AppRoute.Settings -> showSettings()
        AppRoute.Notifications -> showNotifications()
        AppRoute.DownloadSettings -> showDownloadSettings()
        AppRoute.TtsSettings -> showTtsSettings()
        AppRoute.Tabs -> showTabs()
        AppRoute.CleanupRules -> showCleanupRules()
        AppRoute.DataBackup -> showDataBackup()
        AppRoute.Working -> showLibrary()
    }
}
