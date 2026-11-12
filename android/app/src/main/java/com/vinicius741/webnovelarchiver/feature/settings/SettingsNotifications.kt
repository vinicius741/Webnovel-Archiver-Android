package com.vinicius741.webnovelarchiver.feature.settings

import android.os.Build
import android.view.ViewGroup
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.notification.AppNotificationCategory
import com.vinicius741.webnovelarchiver.notification.AppNotificationChannels
import com.vinicius741.webnovelarchiver.notification.NotificationCategoryStatus
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.fullButton
import com.vinicius741.webnovelarchiver.ui.makeButton
import com.vinicius741.webnovelarchiver.ui.makeText
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

    val colors = ThemeManager.colors
    val allowed = permissionGranted && appNotificationsEnabled
    screen(route = AppRoute.Notifications, title = "Notifications", scrollable = true) {
        capSettingsWidth(this)
        tintedCard(if (allowed) colors.tertiary else colors.error) {
            iconColumnRow(R.drawable.wna_notifications, if (allowed) colors.tertiary else colors.error).apply {
                addView(makeEyebrow(context, "App permission", if (allowed) colors.tertiary else colors.error))
                addView(makeText(context, permissionStatus, Type.TITLE_SMALL, colors.onSurface).apply { textSize = TITLE_SP })
                addView(
                    makeText(context, permissionDescription, Type.BODY_SMALL, colors.onSurfaceVariant).apply { setPadding(0, dp(2), 0, 0) },
                )
            }
            addView(
                makeButton(context, notificationPermissionActionLabel(), if (allowed) Btn.SURFACE else Btn.FILLED) {
                    performNotificationPermissionAction()
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin =
                        dp(Space.MD)
                },
            )
        }

        section("Categories")
        settingsGroup {
            notificationCategoryRow(
                host,
                AppNotificationCategory.DOWNLOADS,
                R.drawable.wna_download,
                "Downloads",
                "Progress and queue controls",
            )
            notificationCategoryRow(
                host,
                AppNotificationCategory.TEXT_TO_SPEECH,
                R.drawable.wna_record_voice,
                "Text to Speech",
                "Playback controls",
            )
            notificationCategoryRow(
                host,
                AppNotificationCategory.NARRATION,
                R.drawable.wna_graphic_eq,
                "Narration audio",
                "Generation progress",
            )
            notificationCategoryRow(
                host,
                AppNotificationCategory.AI_GENERATION,
                R.drawable.wna_auto_awesome,
                "AI Generation",
                "Covers in the background",
            )
        }
        settingsNote(
            "Turning off a category hides its notification; it does not stop downloads, speech or AI generation. " +
                "Each category opens its Android settings.",
        )
    }
}

private fun LinearLayout.notificationCategoryRow(
    host: ScreenHost,
    category: AppNotificationCategory,
    icon: Int,
    title: String,
    description: String,
) {
    val status = AppNotificationChannels.status(host.app, category)
    val tint = if (status == NotificationCategoryStatus.ENABLED) ThemeManager.colors.primary else ThemeManager.colors.faint
    navRow(icon, title, "${status.label} · $description", tint = tint) {
        host.app.startActivity(AppNotificationChannels.channelSettingsIntent(host.app, category))
    }
}

private const val TITLE_SP = 15f
