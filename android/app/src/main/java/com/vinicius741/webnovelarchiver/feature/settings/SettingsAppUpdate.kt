package com.vinicius741.webnovelarchiver.feature.settings

import android.app.AlertDialog
import android.content.Intent
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.net.toUri
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.app.appContainer
import com.vinicius741.webnovelarchiver.appupdate.AppUpdateCoordinator
import com.vinicius741.webnovelarchiver.appupdate.AppUpdateInstaller
import com.vinicius741.webnovelarchiver.appupdate.AppUpdateState
import com.vinicius741.webnovelarchiver.appupdate.AvailableUpdate
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.applyAppTheme
import com.vinicius741.webnovelarchiver.ui.card
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.fullButton
import com.vinicius741.webnovelarchiver.ui.makeProgress
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.row
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.section
import com.vinicius741.webnovelarchiver.ui.spacer
import com.vinicius741.webnovelarchiver.ui.styledCheckBox
import com.vinicius741.webnovelarchiver.ui.text
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.launch

/** The download progress views, patched in place so progress ticks skip full re-renders. */
private class DownloadProgressViews(
    val bar: ProgressBar,
    val label: TextView,
)

internal fun ScreenHost.showAppUpdate() {
    val updates = app.appContainer.appUpdates
    rerender = { showAppUpdate() }
    continueInstallIfReady(updates, updates.state.value)
    val rendered = updates.state.value
    var progressViews: DownloadProgressViews? = null
    screen(route = AppRoute.AppUpdate, title = "App Updates", scrollable = true) {
        addView(
            card {
                text("Installed version", Type.LABEL_MEDIUM, ThemeManager.colors.onSurfaceVariant)
                text(updates.installedVersionName, Type.TITLE_MEDIUM)
                spacer(Space.XS)
                text(statusLine(updates, rendered), Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant)
                progressViews = addStateActions(this, updates, rendered)
            },
        )
        section("Options")
        autoCheckRow(this, updates)
        if (!updates.installSupported) {
            spacer(Space.SM)
            text(
                "This is a development build. It can check for releases, but only the release app installs them.",
                Type.BODY_SMALL,
                ThemeManager.colors.onSurfaceVariant,
            )
        }
    }
    screenObserver =
        scope.launch {
            updates.state.collect { state ->
                if (state == rendered || navigator.current != AppRoute.AppUpdate) return@collect
                val views = progressViews
                if (state is AppUpdateState.Downloading && rendered is AppUpdateState.Downloading && views != null) {
                    views.render(state)
                } else {
                    showAppUpdate()
                }
            }
        }
}

private fun ScreenHost.addStateActions(
    parent: LinearLayout,
    updates: AppUpdateCoordinator,
    state: AppUpdateState,
): DownloadProgressViews? =
    with(parent) {
        when (state) {
            is AppUpdateState.Available -> {
                availableActions(this, updates, state.update)
                null
            }
            is AppUpdateState.Downloading -> {
                spacer(Space.MD)
                val bar = makeProgress(context, 0f) as ProgressBar
                addView(bar)
                spacer(Space.XS)
                val label = text("", Type.LABEL_MEDIUM, ThemeManager.colors.onSurfaceVariant)
                fullButton("Cancel download", Btn.OUTLINED, R.drawable.wna_close, bottomMarginDp = 0) {
                    cancelAppUpdateDownload(updates)
                }
                DownloadProgressViews(bar, label).also { it.render(state) }
            }
            is AppUpdateState.ReadyToInstall -> {
                fullButton("Install update", Btn.FILLED, R.drawable.wna_download, bottomMarginDp = 0) {
                    startInstall(updates, state)
                }
                null
            }
            is AppUpdateState.Installing -> {
                fullButton("Installing…", Btn.FILLED, R.drawable.wna_download, enabled = false, bottomMarginDp = 0) {}
                null
            }
            is AppUpdateState.AwaitingConfirmation -> {
                fullButton("Continue install", Btn.FILLED, R.drawable.wna_download, bottomMarginDp = 0) {
                    runCatching { app.startActivity(state.confirmIntent) }
                        .onFailure { toast("Could not open Android's installer") }
                }
                null
            }
            is AppUpdateState.Failed -> {
                val update = state.update
                if (update != null && updates.installSupported) {
                    fullButton("Try again", Btn.FILLED, R.drawable.wna_refresh, bottomMarginDp = 0) {
                        downloadAndInstall(updates, update)
                    }
                }
                checkButton(this, updates, checking = false)
                null
            }
            AppUpdateState.Checking -> {
                checkButton(this, updates, checking = true)
                null
            }
            AppUpdateState.Idle, is AppUpdateState.UpToDate -> {
                checkButton(this, updates, checking = false)
                null
            }
        }
    }

private fun ScreenHost.availableActions(
    parent: LinearLayout,
    updates: AppUpdateCoordinator,
    update: AvailableUpdate,
) = with(parent) {
    spacer(Space.MD)
    text("Version ${update.versionName}", Type.TITLE_SMALL)
    if (update.apk.size > 0) {
        text(
            "Download size ${Formatter.formatShortFileSize(context, update.apk.size)}",
            Type.BODY_SMALL,
            ThemeManager.colors.onSurfaceVariant,
        )
    }
    if (update.tag == updates.preferences.skippedTag) {
        text("You skipped this version. It will not be offered at startup.", Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant)
    }
    fullButton(
        "Download and install",
        Btn.FILLED,
        R.drawable.wna_download,
        enabled = updates.installSupported,
        bottomMarginDp = 0,
    ) {
        downloadAndInstall(updates, update)
    }
    if (update.releaseUrl.isNotBlank()) {
        fullButton("View release", Btn.OUTLINED, R.drawable.wna_open_external, bottomMarginDp = 0) {
            runCatching { app.startActivity(Intent(Intent.ACTION_VIEW, update.releaseUrl.toUri())) }
        }
    }
}

private fun ScreenHost.checkButton(
    parent: LinearLayout,
    updates: AppUpdateCoordinator,
    checking: Boolean,
) = parent.fullButton(
    if (checking) "Checking…" else "Check for updates",
    Btn.TONAL,
    R.drawable.wna_refresh,
    enabled = !checking && updates.checksEnabled,
    bottomMarginDp = 0,
) {
    updates.checkNow()
}

private fun autoCheckRow(
    parent: LinearLayout,
    updates: AppUpdateCoordinator,
) {
    parent.row {
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(makeText(context, "Check automatically", Type.TITLE_SMALL, ThemeManager.colors.onSurface))
                addView(
                    makeText(
                        context,
                        "Look for a new release when the app opens, at most twice a day.",
                        Type.BODY_SMALL,
                        ThemeManager.colors.onSurfaceVariant,
                    ),
                )
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val toggle =
            CheckBox(context).apply {
                text = ""
                isChecked = updates.preferences.autoCheckEnabled
                contentDescription = "Check for updates automatically"
                isEnabled = updates.checksEnabled
            }
        styledCheckBox(toggle)
        toggle.setOnCheckedChangeListener { _, checked -> updates.preferences.autoCheckEnabled = checked }
        addView(toggle)
    }
}

private fun DownloadProgressViews.render(state: AppUpdateState.Downloading) {
    val total = state.totalBytes
    val fraction = if (total > 0) state.downloadedBytes.toFloat() / total else 0f
    bar.progress = (fraction.coerceIn(0f, 1f) * 100).toInt()
    val context = label.context
    val done = Formatter.formatShortFileSize(context, state.downloadedBytes)
    label.text =
        if (total > 0) {
            "${(fraction * 100).toInt()}% · $done of ${Formatter.formatShortFileSize(context, total)}"
        } else {
            done
        }
}

private fun statusLine(
    updates: AppUpdateCoordinator,
    state: AppUpdateState,
): String =
    when (state) {
        AppUpdateState.Idle ->
            if (!updates.checksEnabled) "Update checks are off in the offline simulation" else lastChecked(updates) ?: "Not checked yet"
        AppUpdateState.Checking -> "Checking GitHub for a newer release…"
        is AppUpdateState.UpToDate -> listOfNotNull("You have the latest version", lastChecked(updates)).joinToString(" · ")
        is AppUpdateState.Available -> "A new version is available"
        is AppUpdateState.Downloading -> "Downloading version ${state.update.versionName}…"
        is AppUpdateState.ReadyToInstall -> "Version ${state.update.versionName} is downloaded and verified"
        is AppUpdateState.Installing -> "Handing version ${state.update.versionName} to Android…"
        is AppUpdateState.AwaitingConfirmation -> "Confirm the update in Android's installer"
        is AppUpdateState.Failed -> state.message
    }

private fun lastChecked(updates: AppUpdateCoordinator): String? {
    val checkedAt = updates.preferences.lastCheckedAtMillis.takeIf { it > 0L } ?: return null
    val now = System.currentTimeMillis()
    if (now - checkedAt in 0 until DateUtils.MINUTE_IN_MILLIS) return "Checked just now"
    return "Checked ${DateUtils.getRelativeTimeSpanString(checkedAt, now, DateUtils.MINUTE_IN_MILLIS)}"
}
