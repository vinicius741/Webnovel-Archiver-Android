package com.vinicius741.webnovelarchiver.baselineprofile

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice

/**
 * The profiling build starts with an empty library. Its FixtureLibraryReceiver (app/src/profiling)
 * adds a small generated library through the app's own repository; this broadcasts to it before the
 * journeys run. Seeding is idempotent, so reruns keep the same library.
 */
internal object FixtureLibrary {
    private const val RECEIVER = "com.vinicius741.webnovelarchiver.profiling.FixtureLibraryReceiver"

    fun prepare(targetPackage: String) {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        // A notification-permission prompt on the first launch would cover the Library.
        device.executeShellCommand("pm grant $targetPackage android.permission.POST_NOTIFICATIONS")
        // -f 0x20 (FLAG_INCLUDE_STOPPED_PACKAGES): the freshly installed app has never run.
        val output = device.executeShellCommand("am broadcast -f 0x20 -n $targetPackage/$RECEIVER")
        check("result=-1" in output) { "Fixture library seeding failed: $output" }
        device.executeShellCommand("am force-stop $targetPackage")
    }
}
