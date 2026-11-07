package com.vinicius741.webnovelarchiver.data.diagnostics

import com.vinicius741.webnovelarchiver.data.storage.StorageHealthSnapshot
import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import com.vinicius741.webnovelarchiver.domain.model.Story

/**
 * Privacy-safe aggregates of app state shared by the diagnostic exports: counts and allowlisted
 * categories only — never story ids, titles, URLs, or free-text error details.
 */
object DiagnosticExportPlanning {
    const val MAX_STORAGE_ISSUES = 100

    fun storageIssues(storageHealth: StorageHealthSnapshot): List<DiagnosticStorageIssue> =
        storageHealth.issues.takeLast(MAX_STORAGE_ISSUES).map { issue ->
            DiagnosticStorageIssue(
                document = documentCategory(issue.document),
                kind = issue.kind.name,
                recoveredStoryCount = issue.recoveredStoryCount,
            )
        }

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

    fun librarySummary(stories: List<Story>): DiagnosticLibrarySummary =
        DiagnosticLibrarySummary(
            stories = stories.size,
            chapters = stories.sumOf { it.totalChapters },
            downloadedChapters = stories.sumOf { it.downloadedChapters },
            bySource = stories.groupingBy { story -> story.sourceId?.let(::safeSourceId) ?: "unresolved" }.eachCount().toSortedMap(),
            byStatus = stories.groupingBy { it.status.name }.eachCount().toSortedMap(),
        )

    private fun safeSourceId(sourceId: String): String = LocalDiagnostics.safeToken(sourceId) ?: "other"

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
            "update_follow_settings.json",
            "tts_settings.json",
            "tts_session.json",
        )
}
