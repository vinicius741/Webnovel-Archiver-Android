package com.vinicius741.webnovelarchiver.feature.updates

import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.text

/** The Updates destination: every novel's release forecast. */
internal fun ScreenHost.showUpdates() {
    rerender = { showUpdates() }
    screen(AppRoute.Updates, "Updates", scrollable = true) {
        text("Release forecasts")
    }
}
