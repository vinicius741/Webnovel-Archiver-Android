package com.vinicius741.webnovelarchiver.perf

import android.content.Context
import android.os.Debug
import android.os.SystemClock

/** Memory snapshot reads, isolated from the session runner (PSS + Java heap). */
internal object PerfMemorySampler {
    fun readSnapshot(
        label: String,
        nowNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
    ): PerfRecordedMemory {
        val info = Debug.MemoryInfo()
        runCatching { Debug.getMemoryInfo(info) }
        val runtime = Runtime.getRuntime()
        return PerfRecordedMemory(
            tNanos = nowNanos(),
            label = label.take(40),
            totalPssKb = info.totalPss,
            dalvikPssKb = info.dalvikPss,
            nativePssKb = info.nativePss,
            otherPssKb = info.otherPss,
            javaHeapUsedKb = (runtime.totalMemory() - runtime.freeMemory()) / 1024,
            javaHeapMaxKb = runtime.maxMemory() / 1024,
        )
    }
}

/** Android environment reads for perf sessions, isolated so the recorder stays a thin facade. */
internal object PerfEnvironment {
    private const val DEFAULT_REFRESH_RATE_HZ = 60.0

    /** DisplayManager path works on every supported API without the removed DisplayMetrics field. */
    fun refreshRateHz(activity: android.app.Activity): Double =
        runCatching {
            activity
                .getSystemService(android.hardware.display.DisplayManager::class.java)
                ?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
                ?.refreshRate
                ?.toDouble()
        }.getOrNull() ?: DEFAULT_REFRESH_RATE_HZ

    fun processStartElapsedNanos(): Long = runCatching { android.os.Process.getStartElapsedRealtime() * 1_000_000L }.getOrDefault(0L)

    fun appVersion(): String = com.vinicius741.webnovelarchiver.BuildConfig.VERSION_NAME

    fun deviceMeta(
        appContext: Context,
        appVersion: String,
        refreshRateHz: Double,
    ): PerfDeviceMetaExport =
        PerfDeviceMetaExport(
            appId = appContext.packageName,
            appVersion = appVersion,
            sdkInt = android.os.Build.VERSION.SDK_INT,
            model = android.os.Build.MODEL ?: "unknown",
            densityDpi = appContext.resources.displayMetrics.densityDpi,
            refreshRateHz = refreshRateHz,
        )
}
