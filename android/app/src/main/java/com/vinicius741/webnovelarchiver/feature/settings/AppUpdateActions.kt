package com.vinicius741.webnovelarchiver.feature.settings

import android.app.AlertDialog
import android.content.Intent
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import com.vinicius741.webnovelarchiver.app.appContainer
import com.vinicius741.webnovelarchiver.appupdate.AppUpdateCoordinator
import com.vinicius741.webnovelarchiver.appupdate.AppUpdateState
import com.vinicius741.webnovelarchiver.appupdate.AvailableUpdate
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.applyAppTheme
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.launch

// App-update entry points used outside the App Updates screen (Settings row, startup dialog) and
// the browser hand-off both surfaces share.

/** Settings row summary: installed version plus a found release. */
internal fun appUpdateSummary(updates: AppUpdateCoordinator): String {
    val status = (updates.state.value as? AppUpdateState.Available)?.let { "Version ${it.update.versionName} available" }
    return listOfNotNull("Version ${updates.installedVersionName}", status).joinToString(" · ")
}

internal fun appUpdateNeedsAttention(updates: AppUpdateCoordinator): Boolean {
    val state = updates.state.value as? AppUpdateState.Available ?: return false
    return updates.releaseBuild && state.update.tag != updates.preferences.skippedTag
}

/**
 * Opens the release APK in the browser. The browser downloads it and Android's package installer
 * installs it over this app, which keeps the install-packages permission out of this app.
 */
internal fun ScreenHost.openUpdateDownload(update: AvailableUpdate) {
    openExternal(update.apk.downloadUrl.ifBlank { update.releaseUrl }, "Could not open the browser")
}

internal fun ScreenHost.openExternal(
    url: String,
    failure: String,
) {
    if (url.isBlank()) {
        toast(failure)
        return
    }
    runCatching { app.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
        .onFailure { toast(failure) }
}

/**
 * Runs the throttled automatic check and offers a found release once. Called when the UI is
 * ready and on every resume; the coordinator's interval keeps it to at most two requests a day.
 */
internal fun ScreenHost.maybePromptForAppUpdate() {
    val updates = app.appContainer.appUpdates
    scope.launch {
        val update = updates.autoCheckForPrompt() ?: return@launch
        if (!app.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || app.isFinishing) return@launch
        if (navigator.current == AppRoute.AppUpdate || navigator.current == AppRoute.Working) return@launch
        updates.preferences.promptedTag = update.tag
        AlertDialog
            .Builder(app)
            .setTitle("Update available")
            .setMessage(
                "Version ${update.versionName} is available. You have ${updates.installedVersionName}.\n\n" +
                    "Update downloads it in your browser. Open the downloaded file to install it over this app.",
            ).setPositiveButton("Update") { _, _ -> openUpdateDownload(update) }
            .setNegativeButton("Later", null)
            .setNeutralButton("Skip this version") { _, _ -> updates.preferences.skippedTag = update.tag }
            .show()
            .also { it.applyAppTheme() }
    }
}
