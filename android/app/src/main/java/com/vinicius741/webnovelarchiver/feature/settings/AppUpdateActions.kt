package com.vinicius741.webnovelarchiver.feature.settings

import android.app.AlertDialog
import androidx.lifecycle.Lifecycle
import com.vinicius741.webnovelarchiver.app.appContainer
import com.vinicius741.webnovelarchiver.appupdate.AppUpdateCoordinator
import com.vinicius741.webnovelarchiver.appupdate.AppUpdateInstaller
import com.vinicius741.webnovelarchiver.appupdate.AppUpdateState
import com.vinicius741.webnovelarchiver.appupdate.AvailableUpdate
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.applyAppTheme
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.launch

// App-update entry points used outside the App Updates screen (Settings row, startup dialog) and
// the download -> install hand-off both surfaces share.

/**
 * Release tag whose download should continue straight into the installer once verified (set by
 * "Download and install" or the startup dialog). Process memory only: after a restart the user
 * taps Install on the downloaded update instead.
 */
private var installWhenReadyTag: String? = null

/** Settings row summary: installed version plus anything that needs the user's attention. */
internal fun appUpdateSummary(updates: AppUpdateCoordinator): String {
    val status =
        when (val state = updates.state.value) {
            is AppUpdateState.Available -> "Version ${state.update.versionName} available"
            is AppUpdateState.Downloading -> "Downloading update"
            is AppUpdateState.ReadyToInstall, is AppUpdateState.AwaitingConfirmation -> "Update ready to install"
            else -> null
        }
    return listOfNotNull("Version ${updates.installedVersionName}", status).joinToString(" · ")
}

internal fun appUpdateNeedsAttention(updates: AppUpdateCoordinator): Boolean =
    when (val state = updates.state.value) {
        is AppUpdateState.Available -> updates.installSupported && state.update.tag != updates.preferences.skippedTag
        is AppUpdateState.ReadyToInstall, is AppUpdateState.AwaitingConfirmation -> true
        else -> false
    }

internal fun ScreenHost.downloadAndInstall(
    updates: AppUpdateCoordinator,
    update: AvailableUpdate,
) {
    installWhenReadyTag = update.tag
    updates.download(update)
}

internal fun cancelAppUpdateDownload(updates: AppUpdateCoordinator) {
    installWhenReadyTag = null
    updates.cancelDownload()
}

/** Continues a requested install once the download is verified and Android lets this app install. */
internal fun ScreenHost.continueInstallIfReady(
    updates: AppUpdateCoordinator,
    state: AppUpdateState,
) {
    if (state !is AppUpdateState.ReadyToInstall || state.update.tag != installWhenReadyTag) return
    if (!app.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
    // Cleared first: declining the settings prompt must not reopen it on every render.
    installWhenReadyTag = null
    startInstall(updates, state)
}

internal fun ScreenHost.startInstall(
    updates: AppUpdateCoordinator,
    state: AppUpdateState.ReadyToInstall,
) {
    if (AppUpdateInstaller.canRequestInstalls(app)) {
        updates.install(state.update, state.apk)
        return
    }
    confirm(
        "To install updates, allow Webnovel Archiver to install apps in Android settings, then come back.",
        confirmLabel = "Open settings",
    ) {
        // Back on the App Updates screen, the resumed render continues into the installer.
        installWhenReadyTag = state.update.tag
        runCatching { app.startActivity(AppUpdateInstaller.unknownSourcesSettingsIntent(app)) }
            .onFailure { toast("Could not open Android settings") }
    }
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
            .setMessage("Version ${update.versionName} is available. You have ${updates.installedVersionName}.")
            .setPositiveButton("Update") { _, _ ->
                downloadAndInstall(updates, update)
                showAppUpdate()
            }.setNegativeButton("Later", null)
            .setNeutralButton("Skip this version") { _, _ -> updates.preferences.skippedTag = update.tag }
            .show()
            .also { it.applyAppTheme() }
    }
}
