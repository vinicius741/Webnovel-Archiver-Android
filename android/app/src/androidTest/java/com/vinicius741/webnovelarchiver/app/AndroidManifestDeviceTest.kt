package com.vinicius741.webnovelarchiver.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.vinicius741.webnovelarchiver.download.DownloadForegroundService
import com.vinicius741.webnovelarchiver.feature.browser.CloudflareSolveActivity
import com.vinicius741.webnovelarchiver.tts.TtsForegroundService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class AndroidManifestDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val packageManager = context.packageManager

    @Test
    fun foregroundServicesArePrivateAndDeclareTheirRequiredTypes() {
        assertServiceType(
            DownloadForegroundService::class.java,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        assertServiceType(
            TtsForegroundService::class.java,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
    }

    @Test
    fun activitiesHaveExpectedExportBoundaries() {
        val main = packageManager.getActivityInfo(ComponentName(context, MainActivity::class.java), 0)
        val cloudflare = packageManager.getActivityInfo(ComponentName(context, CloudflareSolveActivity::class.java), 0)

        assertTrue(main.exported)
        assertFalse(cloudflare.exported)
    }

    @Test
    fun foregroundServicePermissionsArePackaged() {
        val permissions =
            packageManager
                .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions
                .orEmpty()
                .toSet()

        assertTrue(Manifest.permission.FOREGROUND_SERVICE in permissions)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            assertTrue(Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC in permissions)
            assertTrue(Manifest.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK in permissions)
        }
    }

    private fun assertServiceType(
        serviceClass: Class<*>,
        expectedType: Int,
    ) {
        val info = packageManager.getServiceInfo(ComponentName(context, serviceClass), 0)
        assertFalse(info.exported)
        assertEquals(expectedType, info.foregroundServiceType and expectedType)
    }
}
