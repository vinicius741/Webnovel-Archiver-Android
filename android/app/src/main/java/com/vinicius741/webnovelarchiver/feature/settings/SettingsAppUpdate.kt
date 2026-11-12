package com.vinicius741.webnovelarchiver.feature.settings

import android.content.res.ColorStateList
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
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
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.fullButton
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.row
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.section
import com.vinicius741.webnovelarchiver.ui.spacer
import com.vinicius741.webnovelarchiver.ui.styledCheckBox
import com.vinicius741.webnovelarchiver.ui.text
import com.vinicius741.webnovelarchiver.ui.tintedIcon
import kotlinx.coroutines.launch

internal fun ScreenHost.showAppUpdate() {
    val updates = app.appContainer.appUpdates
    rerender = { showAppUpdate() }
    val rendered = updates.state.value
    val colors = ThemeManager.colors
    screen(route = AppRoute.AppUpdate, title = "App updates", scrollable = true) {
        capSettingsWidth(this)
        tintedCard(colors.primary) {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(
                ImageView(context).apply {
                    setImageDrawable(context.tintedIcon(R.drawable.wna_menu_book, colors.onPrimary))
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    background = roundedBg(colors.primary, dp(APP_ICON_RADIUS_DP).toFloat())
                    val pad = dp(Space.LG)
                    setPadding(pad, pad, pad, pad)
                },
                LinearLayout.LayoutParams(dp(APP_ICON_DP), dp(APP_ICON_DP)),
            )
            addView(
                centered(makeText(context, "Installed version", Type.BODY_SMALL, colors.onSurfaceVariant)).apply {
                    setPadding(0, dp(Space.MD), 0, 0)
                },
            )
            addView(
                centered(
                    makeText(context, updates.installedVersionName, Type.HEADLINE, colors.onSurface),
                ).apply { setPadding(0, dp(2), 0, 0) },
            )
            addView(statusRow(context, rendered, updates))
            addStateActions(this, updates, rendered)
        }
        section("Options")
        settingsGroup {
            switchRow(
                "Check automatically",
                "When the app opens, at most twice a day",
                updates.preferences.autoCheckEnabled,
                enabled = updates.checksEnabled,
            ) { on -> updates.preferences.autoCheckEnabled = on }
        }
        settingsNote(
            if (updates.releaseBuild) {
                "Updates download in your browser. Open the downloaded file and Android installs it over this app, " +
                    "keeping your library. The first time, Android asks you to let the browser install apps."
            } else {
                "This is a development build. It can check for releases, but only the release app installs them."
            },
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

private fun centered(view: TextView): TextView =
    view.apply {
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

/** The state line under the version: an icon (or spinner) and the plain-language status. */
private fun statusRow(
    context: android.content.Context,
    state: AppUpdateState,
    updates: AppUpdateCoordinator,
): LinearLayout {
    val colors = ThemeManager.colors
    val (icon, tint) =
        when (state) {
            is AppUpdateState.UpToDate -> R.drawable.wna_check_circle to colors.tertiary
            is AppUpdateState.Available -> R.drawable.wna_download to colors.primary
            is AppUpdateState.Failed -> R.drawable.wna_error to colors.error
            AppUpdateState.Checking, AppUpdateState.Idle -> R.drawable.wna_schedule to colors.onSurfaceVariant
        }
    return LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(0, context.dp(Space.SM), 0, 0)
        if (state == AppUpdateState.Checking) {
            addView(
                ProgressBar(context).apply { indeterminateTintList = ColorStateList.valueOf(colors.primary) },
                LinearLayout
                    .LayoutParams(
                        context.dp(STATUS_ICON_DP),
                        context.dp(STATUS_ICON_DP),
                    ).apply { marginEnd = context.dp(Space.SM) },
            )
        } else {
            addView(
                ImageView(context).apply { setImageDrawable(context.tintedIcon(icon, tint)) },
                LinearLayout
                    .LayoutParams(
                        context.dp(STATUS_ICON_DP),
                        context.dp(STATUS_ICON_DP),
                    ).apply { marginEnd = context.dp(Space.SM) },
            )
        }
        addView(makeText(context, statusLine(updates, state), Type.BODY_SMALL, colors.onSurface).apply { gravity = Gravity.CENTER })
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
    Btn.FILLED,
    R.drawable.wna_refresh,
    enabled = !checking && updates.checksEnabled,
    bottomMarginDp = 0,
) {
    updates.checkNow()
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

private const val APP_ICON_DP = 64
private const val APP_ICON_RADIUS_DP = 20
private const val STATUS_ICON_DP = 18
