package com.vinicius741.webnovelarchiver.feature.settings

import android.os.Build
import android.view.ViewGroup
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.notification.AppNotificationCategory
import com.vinicius741.webnovelarchiver.notification.AppNotificationChannels
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.card
import com.vinicius741.webnovelarchiver.ui.fullButton
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.section
import com.vinicius741.webnovelarchiver.ui.settingRow
import com.vinicius741.webnovelarchiver.ui.spacer
import com.vinicius741.webnovelarchiver.ui.text

internal fun ScreenHost.showNotifications() {
    val host = this
    AppNotificationChannels.ensureCreated(app)
    rerender = { showNotifications() }
    val appNotificationsEnabled = AppNotificationChannels.areAppNotificationsEnabled(app)
    val permissionGranted = AppNotificationChannels.hasPostNotificationsPermission(app)
    val permissionStatus =
        when {
            !permissionGranted -> "Not allowed for downloads"
            !appNotificationsEnabled -> "Blocked in Android settings"
            else -> "Allowed"
        }
    val permissionDescription =
        when {
            !permissionGranted ->
                "Download progress is hidden until you allow notifications. Text-to-speech media controls can still appear."
            !appNotificationsEnabled ->
                "Android is currently blocking app notifications. Open system settings to change this."
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                "The app can show download progress. Text-to-speech media controls are managed by their own channel."
            else -> "Android is allowing this app to show notifications."
        }

    screen(route = AppRoute.Notifications, title = "Notifications", onBack = { showSettings() }, scrollable = true) {
        section("App Permission")
        addView(
            card {
                text(permissionStatus, Type.TITLE_SMALL)
                spacer(Space.XS)
                text(permissionDescription, Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant)
                fullButton(
                    label = notificationPermissionActionLabel(),
                    variant = Btn.TONAL,
                    topMarginDp = Space.MD,
                    bottomMarginDp = 0,
                ) {
                    performNotificationPermissionAction()
                }
            },
        )

        section("Categories")
        notificationCategoryRow(
            host = host,
            category = AppNotificationCategory.DOWNLOADS,
            icon = R.drawable.wna_download,
            title = "Downloads",
            description = "Progress and queue controls while chapters are downloading.",
        )
        notificationCategoryRow(
            host = host,
            category = AppNotificationCategory.TEXT_TO_SPEECH,
            icon = R.drawable.wna_speaker,
            title = "Text to Speech",
            description = "Playback controls while the app reads a chapter aloud.",
        )

        spacer(Space.SM)
        text(
            "Turning off a category hides its notification; it does not disable downloads or text-to-speech. " +
                "Android may still show foreground work in its running-apps panel.",
            Type.BODY_SMALL,
            ThemeManager.colors.onSurfaceVariant,
        )
    }
}

private fun ViewGroup.notificationCategoryRow(
    host: ScreenHost,
    category: AppNotificationCategory,
    icon: Int,
    title: String,
    description: String,
) {
    val status = AppNotificationChannels.status(host.app, category)
    settingRow(
        iconRes = icon,
        title = "$title · ${status.label}",
        description = description,
    ) {
        host.app.startActivity(AppNotificationChannels.channelSettingsIntent(host.app, category))
    }
}
