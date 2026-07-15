package com.vinicius741.webnovelarchiver.feature.downloads

import com.vinicius741.webnovelarchiver.download.DownloadForegroundService
import com.vinicius741.webnovelarchiver.navigation.ScreenHost

internal fun ScreenHost.startDownloadForegroundService() {
    requestNotificationPermissionForDownload()
    DownloadForegroundService.start(app)
}
