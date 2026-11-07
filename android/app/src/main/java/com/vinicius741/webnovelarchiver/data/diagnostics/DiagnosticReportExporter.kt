package com.vinicius741.webnovelarchiver.data.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.webkit.WebView
import androidx.annotation.RequiresApi
import com.vinicius741.webnovelarchiver.BuildConfig
import com.vinicius741.webnovelarchiver.data.repository.AppRepository
import com.vinicius741.webnovelarchiver.data.storage.AtomicFileWrites
import com.vinicius741.webnovelarchiver.data.storage.SharedGson
import com.vinicius741.webnovelarchiver.source.network.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.TimeZone

/**
 * Writes the app-wide diagnostic report (Settings → "Share Diagnostic Report"): a single JSON
 * document with a time window of every journal record plus a snapshot of app, device, library,
 * queue, settings, and source state. Layout and trimming live in [DiagnosticReportPlanning].
 */
object DiagnosticReportExporter {
    private const val FILE_PREFIX = "webnovel_diagnostic_report_"
    private const val KEEP_REPORTS = 3
    private const val MAX_EXIT_REASONS = 16
    private const val MAX_ANR_TRACE_LINES = 60

    suspend fun export(
        context: Context,
        directory: File,
        repository: AppRepository,
        network: NetworkClient,
        window: DiagnosticWindow,
        generatedAtMillis: Long = System.currentTimeMillis(),
    ): File =
        withContext(Dispatchers.IO) {
            val journal = checkNotNull(DiagnosticJournal.active) { "Diagnostics are unavailable" }
            val since = generatedAtMillis - window.millis
            val collector = DiagnosticReportPlanning.Collector()
            journal.forEachRecord(since, collector::accept)
            val json =
                DiagnosticReportPlanning.document(
                    window = window,
                    generatedAtMillis = generatedAtMillis,
                    context = stateSections(context, repository, network, journal),
                    exitReasons = exitReasons(context, since),
                    collector = collector,
                    serialize = SharedGson.plain::toJson,
                )
            val file = File(directory, "$FILE_PREFIX$generatedAtMillis.json")
            AtomicFileWrites.writeText(file, json)
            pruneOldReports(directory, FILE_PREFIX, KEEP_REPORTS)
            file
        }

    private fun stateSections(
        context: Context,
        repository: AppRepository,
        network: NetworkClient,
        journal: DiagnosticJournal,
    ): Map<String, Any?> =
        linkedMapOf(
            "app" to appSection(),
            "device" to deviceSection(context),
            "library" to runCatching { DiagnosticExportPlanning.librarySummary(repository.library()) }.getOrNull(),
            "queue" to runCatching { DiagnosticExportPlanning.queueSummary(repository.queue()) }.getOrNull(),
            "storageIssues" to runCatching { DiagnosticExportPlanning.storageIssues(repository.getStorageHealth()) }.getOrNull(),
            "settings" to
                runCatching {
                    linkedMapOf(
                        "download" to repository.settings.get(),
                        "sourceOverrides" to
                            repository.sourceDownloadSettings
                                .get()
                                .mapKeys { (source, _) -> LocalDiagnostics.safeToken(source) ?: "other" },
                    )
                }.getOrNull(),
            "sources" to runCatching { network.reliabilitySnapshots() }.getOrNull(),
            "journal" to journal.stats(),
        )

    private fun appSection(): Map<String, Any?> =
        linkedMapOf(
            "versionName" to BuildConfig.VERSION_NAME,
            "versionCode" to BuildConfig.VERSION_CODE.toLong(),
            "buildType" to BuildConfig.BUILD_TYPE,
            "applicationId" to BuildConfig.APPLICATION_ID,
            "obfuscated" to !BuildConfig.DEBUG,
            "processUptimeMs" to SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime(),
        )

    private fun deviceSection(context: Context): Map<String, Any?> {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val powerManager = context.getSystemService(PowerManager::class.java)
        val memory = ActivityManager.MemoryInfo().also { info -> activityManager?.getMemoryInfo(info) }
        return linkedMapOf(
            "manufacturer" to Build.MANUFACTURER,
            "model" to Build.MODEL,
            "sdkInt" to Build.VERSION.SDK_INT,
            "release" to Build.VERSION.RELEASE,
            "abi" to Build.SUPPORTED_ABIS.firstOrNull(),
            "webView" to runCatching { WebView.getCurrentWebViewPackage()?.versionName }.getOrNull(),
            "utcOffsetMinutes" to TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000,
            "memoryClassMb" to activityManager?.memoryClass,
            "lowRamDevice" to activityManager?.isLowRamDevice,
            "availableMemoryMb" to memory.availMem / (1024 * 1024),
            "lowMemory" to memory.lowMemory,
            "freeStorageMb" to context.filesDir.usableSpace / (1024 * 1024),
            "ignoringBatteryOptimizations" to runCatching { powerManager?.isIgnoringBatteryOptimizations(context.packageName) }.getOrNull(),
            "backgroundRestricted" to
                if (Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.P
                ) {
                    runCatching { activityManager?.isBackgroundRestricted }.getOrNull()
                } else {
                    null
                },
            "powerSaveMode" to powerManager?.isPowerSaveMode,
        )
    }

    /** Android's record of how recent app processes ended (API 30+), limited to the window. */
    private fun exitReasons(
        context: Context,
        sinceMillis: Long,
    ): List<Map<String, Any?>> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
        return runCatching {
            activityManager
                .getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXIT_REASONS)
                .filter { it.timestamp >= sinceMillis }
                .sortedBy { it.timestamp }
                .map { info ->
                    linkedMapOf(
                        "ts" to info.timestamp,
                        "reason" to exitReasonName(info.reason),
                        "status" to info.status,
                        "importance" to info.importance,
                        "description" to DiagnosticRedactor.redact(info.description),
                        "pssKb" to info.pss,
                        "rssKb" to info.rss,
                        "mainThreadTrace" to if (info.reason == ApplicationExitInfo.REASON_ANR) anrMainThread(info) else null,
                    )
                }
        }.getOrDefault(emptyList())
    }

    /** The `"main"` thread block of an ANR trace: the stack that was blocked. Frames are code locations only. */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun anrMainThread(info: ApplicationExitInfo): List<String>? =
        runCatching {
            info.traceInputStream?.bufferedReader()?.useLines { lines ->
                lines
                    .dropWhile { !it.startsWith("\"main\"") }
                    .takeWhile { it.isNotBlank() }
                    .take(MAX_ANR_TRACE_LINES)
                    .toList()
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun exitReasonName(reason: Int): String =
        when (reason) {
            ApplicationExitInfo.REASON_EXIT_SELF -> "exit_self"
            ApplicationExitInfo.REASON_SIGNALED -> "signaled"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "low_memory"
            ApplicationExitInfo.REASON_CRASH -> "crash"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash_native"
            ApplicationExitInfo.REASON_ANR -> "anr"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization_failure"
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission_change"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive_resource_usage"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "user_requested"
            ApplicationExitInfo.REASON_USER_STOPPED -> "user_stopped"
            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency_died"
            ApplicationExitInfo.REASON_OTHER -> "other"
            ApplicationExitInfo.REASON_FREEZER -> "freezer"
            ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "package_state_change"
            ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "package_updated"
            else -> "unknown_$reason"
        }
}

/** Keeps the newest [keep] exports with [prefix] in [directory]; older ones are deleted. */
internal fun pruneOldReports(
    directory: File,
    prefix: String,
    keep: Int,
) {
    directory
        .listFiles { file -> file.name.startsWith(prefix) }
        ?.sortedByDescending { it.name }
        ?.drop(keep)
        ?.forEach { it.delete() }
}
