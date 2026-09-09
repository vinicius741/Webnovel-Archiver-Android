package com.vinicius741.webnovelarchiver.app

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.vinicius741.webnovelarchiver.ai.AiChapterRewriteForegroundService
import com.vinicius741.webnovelarchiver.ai.AiCoverForegroundService
import com.vinicius741.webnovelarchiver.ai.aiJobNotification
import com.vinicius741.webnovelarchiver.notification.AppNotificationChannels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiJobNotificationDeviceTest {
    @get:Rule val permission = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    @Test
    fun ongoingAndResultNotificationsCoexistWithDistinctIntents() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AppNotificationChannels.ensureCreated(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val notifications =
            (1..4).map { request ->
                context.aiJobNotification("Job $request", "Progress $request", request, ongoing = request % 2 == 0)
            }
        try {
            notifications.forEachIndexed { index, notification ->
                val ongoing = index % 2 == 1
                assertEquals(ongoing, notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
                assertEquals(!ongoing, notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
                assertEquals(ongoing, notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
                assertEquals("Progress ${index + 1}", notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
                manager.notify(1003 + index, notification)
            }
            assertEquals(4, notifications.map { it.contentIntent }.toSet().size)
            assertTrue(manager.activeNotifications.map { it.id }.containsAll((1003..1006).toList()))
        } finally {
            (1003..1006).forEach(manager::cancel)
        }
    }

    @Test
    fun idleForegroundServicesStopWithoutLeavingNotifications() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            scenario.onActivity {
                AiCoverForegroundService.start(it)
                assertTrue(AiChapterRewriteForegroundService.start(it))
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            androidx.test.platform.app.InstrumentationRegistry
                .getInstrumentation()
                .waitForIdleSync()
            val deadline = System.currentTimeMillis() + 5000
            while (manager.activeNotifications.any { it.id == 1003 || it.id == 1005 } &&
                System.currentTimeMillis() < deadline
            ) {
                Thread.sleep(50)
            }
            assertFalse(manager.activeNotifications.any { it.id == 1003 || it.id == 1005 })
        }
    }
}
