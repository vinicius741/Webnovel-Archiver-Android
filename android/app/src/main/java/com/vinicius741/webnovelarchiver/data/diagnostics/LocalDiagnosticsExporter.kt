package com.vinicius741.webnovelarchiver.data.diagnostics

import android.content.Context
import com.google.gson.GsonBuilder
import com.vinicius741.webnovelarchiver.BuildConfig
import com.vinicius741.webnovelarchiver.data.storage.AtomicFileWrites
import com.vinicius741.webnovelarchiver.data.storage.StorageHealthSnapshot
import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import java.io.File

object DiagnosticExportPlanning {
    const val MAX_STORAGE_ISSUES = 100

    fun payload(
        app: DiagnosticAppInfo,
        storageHealth: StorageHealthSnapshot,
        queue: List<DownloadJob>,
        events: List<DiagnosticEvent>,
        generatedAtMillis: Long,
    ): DiagnosticExportPayload =
        DiagnosticExportPayload(
            generatedAtMillis = generatedAtMillis,
            app = app,
            storageIssues =
                storageHealth.issues.takeLast(MAX_STORAGE_ISSUES).map { issue ->
                    DiagnosticStorageIssue(
                        document = documentCategory(issue.document),
                        kind = issue.kind.name,
                        recoveredStoryCount = issue.recoveredStoryCount,
                    )
                },
            queue = queueSummary(queue),
            warningAndErrorEvents = events.takeLast(LocalDiagnostics.MAX_EVENTS),
        )

    fun queueSummary(queue: List<DownloadJob>): DiagnosticQueueSummary =
        DiagnosticQueueSummary(
            total = queue.size,
            byStatus = queue.groupingBy { it.status.takeIf(DOWNLOAD_STATUSES::contains) ?: "other" }.eachCount().toSortedMap(),
            byErrorCategory =
                queue
                    .mapNotNull { it.errorCategory?.takeIf(ERROR_CATEGORIES::contains) }
                    .groupingBy { it }
                    .eachCount()
                    .toSortedMap(),
        )

    private fun documentCategory(document: String): String = document.takeIf(SAFE_DOCUMENTS::contains) ?: "story_document.json"

    private val DOWNLOAD_STATUSES = setOf("pending", "downloading", "paused", "completed", "failed", "cancelled")
    private val ERROR_CATEGORIES =
        setOf("network", "rate_limit", "parse", "cancelled", "missing_story", "missing_provider", "invalid_chapter", "unknown")
    private val SAFE_DOCUMENTS =
        setOf(
            "library_index.json",
            "settings.json",
            "source_download_settings.json",
            "chapter_filter_settings.json",
            "display_preferences.json",
            "tabs.json",
            "sentence_removal.json",
            "regex_cleanup_rules.json",
            "download_queue.json",
            "update_followed_story_ids.json",
            "tts_settings.json",
            "tts_session.json",
        )
}

object LocalDiagnosticsExporter {
    private const val MAX_EXPORT_BYTES = 256 * 1024

    fun export(
        context: Context,
        storageHealth: StorageHealthSnapshot,
        queue: List<DownloadJob>,
        generatedAtMillis: Long = System.currentTimeMillis(),
    ): File {
        val payload =
            DiagnosticExportPlanning.payload(
                app =
                    DiagnosticAppInfo(
                        versionName = BuildConfig.VERSION_NAME,
                        versionCode = BuildConfig.VERSION_CODE.toLong(),
                        buildType = BuildConfig.BUILD_TYPE,
                        sdkInt = android.os.Build.VERSION.SDK_INT,
                    ),
                storageHealth = storageHealth,
                queue = queue,
                events = LocalDiagnostics.snapshot(),
                generatedAtMillis = generatedAtMillis,
            )
        val json = GsonBuilder().setPrettyPrinting().create().toJson(payload)
        check(json.toByteArray().size <= MAX_EXPORT_BYTES) { "Diagnostic export exceeded its size limit" }
        val directory = File(context.cacheDir, "diagnostics").apply { mkdirs() }
        return File(directory, "webnovel_diagnostics_$generatedAtMillis.json").also {
            AtomicFileWrites.writeText(it, json)
        }
    }
}
