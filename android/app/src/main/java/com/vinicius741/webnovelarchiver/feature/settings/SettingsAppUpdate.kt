package com.vinicius741.webnovelarchiver.feature.settings

import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.app.appContainer
import com.vinicius741.webnovelarchiver.appupdate.AppUpdateCoordinator
import com.vinicius741.webnovelarchiver.appupdate.AppUpdateState
import com.vinicius741.webnovelarchiver.appupdate.AvailableUpdate
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.card
import com.vinicius741.webnovelarchiver.ui.fullButton
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.row
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.section
import com.vinicius741.webnovelarchiver.ui.spacer
import com.vinicius741.webnovelarchiver.ui.styledCheckBox
import com.vinicius741.webnovelarchiver.ui.text
import kotlinx.coroutines.launch

internal fun ScreenHost.showAppUpdate() {
    val updates = app.appContainer.appUpdates
    rerender = { showAppUpdate() }
    val rendered = updates.state.value
    screen(route = AppRoute.AppUpdate, title = "App Updates", scrollable = true) {
        addView(
            card {
                text("Installed version", Type.LABEL_MEDIUM, ThemeManager.colors.onSurfaceVariant)
                text(updates.installedVersionName, Type.TITLE_MEDIUM)
                spacer(Space.XS)
                text(statusLine(updates, rendered), Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant)
                addStateActions(this, updates, rendered)
            },
        )
        section("Options")
        autoCheckRow(this, updates)
        spacer(Space.SM)
        text(
            if (updates.releaseBuild) {
                "Updates download in your browser. Open the downloaded file and Android installs it over this app, " +
                    "keeping your library. The first time, Android asks you to let the browser install apps."
            } else {
                "This is a development build. It can check for releases, but only the release app installs them."
            },
            Type.BODY_SMALL,
            ThemeManager.colors.onSurfaceVariant,
        )
    }
    screenObserver =
        scope.launch {
            updates.state.collect { state ->
                if (state == rendered || navigator.current != AppRoute.AppUpdate) return@collect
                showAppUpdate()
            }
        }
}

private fun ScreenHost.addStateActions(
    parent: LinearLayout,
    updates: AppUpdateCoordinator,
    state: AppUpdateState,
) = with(parent) {
    when (state) {
        is AppUpdateState.Available -> availableActions(this, updates, state.update)
        is AppUpdateState.Failed -> {
            val update = state.update
            if (update != null && updates.releaseBuild) {
                fullButton("Download update", Btn.FILLED, R.drawable.wna_download, bottomMarginDp = 0) {
                    openUpdateDownload(update)
                }
            }
            checkButton(this, updates, checking = false)
        }
        AppUpdateState.Checking -> checkButton(this, updates, checking = true)
        AppUpdateState.Idle, is AppUpdateState.UpToDate -> checkButton(this, updates, checking = false)
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
        "Download update",
        Btn.FILLED,
        R.drawable.wna_download,
        enabled = updates.releaseBuild,
        bottomMarginDp = 0,
    ) {
        openUpdateDownload(update)
    }
    if (update.releaseUrl.isNotBlank()) {
        fullButton("View release", Btn.OUTLINED, R.drawable.wna_open_external, bottomMarginDp = 0) {
            openExternal(update.releaseUrl, "Could not open the release page")
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
        is AppUpdateState.Failed -> state.message
    }

private fun lastChecked(updates: AppUpdateCoordinator): String? {
    val checkedAt = updates.preferences.lastCheckedAtMillis.takeIf { it > 0L } ?: return null
    val now = System.currentTimeMillis()
    if (now - checkedAt in 0 until DateUtils.MINUTE_IN_MILLIS) return "Checked just now"
    return "Checked ${DateUtils.getRelativeTimeSpanString(checkedAt, now, DateUtils.MINUTE_IN_MILLIS)}"
}
