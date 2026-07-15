package com.vinicius741.webnovelarchiver.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationSettingsPlanningTest {
    @Test
    fun enabledChannelIsEnabledWhenTheAppCanPost() {
        assertEquals(
            NotificationCategoryStatus.ENABLED,
            NotificationSettingsPlanning.categoryStatus(
                AppNotificationCategory.DOWNLOADS,
                appNotificationsEnabled = true,
                channelEnabled = true,
            ),
        )
    }

    @Test
    fun disabledChannelWinsOverAppPermissionState() {
        assertEquals(
            NotificationCategoryStatus.OFF_FOR_CATEGORY,
            NotificationSettingsPlanning.categoryStatus(
                AppNotificationCategory.DOWNLOADS,
                appNotificationsEnabled = false,
                channelEnabled = false,
            ),
        )
    }

    @Test
    fun downloadChannelReportsAppPermissionBlock() {
        assertEquals(
            NotificationCategoryStatus.BLOCKED_BY_APP_PERMISSION,
            NotificationSettingsPlanning.categoryStatus(
                AppNotificationCategory.DOWNLOADS,
                appNotificationsEnabled = false,
                channelEnabled = true,
            ),
        )
    }

    @Test
    fun mediaChannelRemainsEnabledWithoutPostNotificationsPermission() {
        assertEquals(
            NotificationCategoryStatus.ENABLED,
            NotificationSettingsPlanning.categoryStatus(
                AppNotificationCategory.TEXT_TO_SPEECH,
                appNotificationsEnabled = false,
                channelEnabled = true,
            ),
        )
    }

    @Test
    fun contextualPermissionRequestIsOfferedOnlyOnce() {
        assertTrue(
            NotificationSettingsPlanning.shouldRequestAutomatically(
                runtimePermissionRequired = true,
                permissionGranted = false,
                automaticPromptShown = false,
            ),
        )
        assertFalse(
            NotificationSettingsPlanning.shouldRequestAutomatically(
                runtimePermissionRequired = true,
                permissionGranted = false,
                automaticPromptShown = true,
            ),
        )
        assertFalse(
            NotificationSettingsPlanning.shouldRequestAutomatically(
                runtimePermissionRequired = true,
                permissionGranted = true,
                automaticPromptShown = false,
            ),
        )
    }

    @Test
    fun settingsActionFallsBackToSystemSettingsAfterThePromptWasShown() {
        assertEquals(
            NotificationPermissionAction.REQUEST_PERMISSION,
            NotificationSettingsPlanning.settingsAction(
                runtimePermissionRequired = true,
                permissionGranted = false,
                automaticPromptShown = false,
            ),
        )
        assertEquals(
            NotificationPermissionAction.OPEN_APP_SETTINGS,
            NotificationSettingsPlanning.settingsAction(
                runtimePermissionRequired = true,
                permissionGranted = false,
                automaticPromptShown = true,
            ),
        )
    }
}
