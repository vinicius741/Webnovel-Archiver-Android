package com.vinicius741.webnovelarchiver.app

import com.vinicius741.webnovelarchiver.BuildConfig
import com.vinicius741.webnovelarchiver.perf.PerfInstrumentation
import com.vinicius741.webnovelarchiver.perf.PerfRecorder

// Debug perf-session hooks split out of MainActivity so the activity file stays within its
// line budget; every function is a no-op unless a perf session is active.

internal fun MainActivity.startPerfSessionIfRequested() {
    if (BuildConfig.DEBUG) PerfRecorder.maybeStartFromIntent(this, intent)
}

internal fun MainActivity.recordUiReadyForPerf() {
    if (BuildConfig.DEBUG) PerfInstrumentation.recordUiReady()
}

internal fun MainActivity.recordInteractionForPerf() {
    if (BuildConfig.DEBUG) PerfRecorder.recordInteraction()
}

internal fun MainActivity.finishPerfSession() {
    if (BuildConfig.DEBUG) PerfRecorder.onActivityDestroyed()
}
