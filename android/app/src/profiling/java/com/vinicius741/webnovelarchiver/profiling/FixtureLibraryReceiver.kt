package com.vinicius741.webnovelarchiver.profiling

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vinicius741.webnovelarchiver.app.WebnovelArchiverApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Seeds the profiling app's empty library with [ProfilingFixture] so the Baseline Profile generator
 * has stories to open. Only the profiling builds (nonMinifiedRelease, benchmarkRelease) contain it;
 * they have their own application id, so this never touches the debug or release library.
 *
 * `adb shell am broadcast -f 0x20 -n <app id>/com.vinicius741.webnovelarchiver.profiling.FixtureLibraryReceiver`
 * prints `result=-1` and `data="seeded N"` on success.
 */
class FixtureLibraryReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val pending = goAsync()
        val app = context.applicationContext as WebnovelArchiverApp
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val added = ProfilingFixture.seed(app.awaitContainer().repository)
                pending.setResult(Activity.RESULT_OK, "seeded $added", null)
            } catch (
                @Suppress("TooGenericExceptionCaught") error: Exception,
            ) {
                pending.setResult(Activity.RESULT_CANCELED, "failed: $error", null)
            } finally {
                pending.finish()
            }
        }
    }
}
