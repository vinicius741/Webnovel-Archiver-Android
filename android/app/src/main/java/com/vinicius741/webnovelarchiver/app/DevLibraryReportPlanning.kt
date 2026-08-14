package com.vinicius741.webnovelarchiver.app

import com.google.gson.GsonBuilder
import com.vinicius741.webnovelarchiver.data.storage.StorageHealthIssue
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.model.Tab
import java.security.MessageDigest

/**
 * Debug-only "dev library report" planning (agent QA convenience).
 *
 * Backs the `dev_library_report` intent extra: when a cold start carries it, [MainActivity]
 * (gated on `BuildConfig.DEBUG`, so this is dead in release) writes a JSON snapshot of what the
 * app actually hydrated from storage to `<cacheDir>/dev_library_report.json`, where an agent can
 * read it back with `adb shell run-as <pkg> cat cache/dev_library_report.json`. This turns
 * "did the restore actually load?" from screenshot/UI-dump guesswork into a byte-comparable check.
 *
 * The report's [DevLibraryReport.storyIdsSha256] is `sha256(ids.joinToString("\n"))` (UTF-8,
 * lowercase hex) over the library in loaded order. The seeding tooling computes the same hash from
 * the backup's `library[].id` list, so "hashes match" proves the app loaded exactly the intended
 * stories in exactly the intended order — and `storageIssues` surfaces any document the storage
 * layer quarantined as corrupt (a silently-dropped story file otherwise looks like a smaller
 * library with no error anywhere).
 *
 * Planning is pure (no `Intent`/`File` dependency) and unit-testable, mirroring [DevLaunchPlanning].
 */
object DevLibraryReportPlanning {
    /** Intent extra requesting the report. Truthy values: "1" or "true" (case-insensitive, trimmed). */
    const val EXTRA_DEV_LIBRARY_REPORT = "dev_library_report"

    /** Report destination, relative to the app's cacheDir. */
    const val REPORT_FILENAME = "dev_library_report.json"

    private val gson = GsonBuilder().setPrettyPrinting().create()

    data class DevLibraryTabReport(
        val id: String,
        val name: String,
        val order: Int,
        val storyCount: Int,
    )

    data class DevStorageIssueReport(
        val document: String,
        val kind: String,
        val detail: String,
        val recoveredStoryCount: Int,
    )

    data class DevLibraryReport(
        val generatedAt: Long,
        val appVersion: String?,
        val librarySize: Int,
        /** sha256 of the story ids joined with "\n" (UTF-8), in loaded order — see object KDoc. */
        val storyIdsSha256: String,
        val firstStoryId: String? = null,
        val lastStoryId: String? = null,
        val totalChapterEntries: Int,
        val downloadedChapterEntries: Int,
        val tabs: List<DevLibraryTabReport>,
        val untabbedStories: Int,
        val storageIssues: List<DevStorageIssueReport>,
    )

    /** True when the extra asks for a report; anything but "1"/"true" (trimmed, case-insensitive) is ignored. */
    fun requested(extra: String?): Boolean = extra?.trim()?.lowercase() in setOf("1", "true")

    /** Stable identity of the loaded library; the seeding tool computes the identical value host-side. */
    fun storyIdsSha256(ids: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest
            .digest(ids.joinToString("\n").toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun build(
        library: List<Story>,
        tabs: List<Tab>,
        storageIssues: List<StorageHealthIssue>,
        appVersion: String?,
        generatedAt: Long = System.currentTimeMillis(),
    ): DevLibraryReport {
        val storyIds = library.map { it.id }
        val storiesPerTab = library.groupingBy { it.tabId }.eachCount()
        return DevLibraryReport(
            generatedAt = generatedAt,
            appVersion = appVersion,
            librarySize = library.size,
            storyIdsSha256 = storyIdsSha256(storyIds),
            firstStoryId = storyIds.firstOrNull(),
            lastStoryId = storyIds.lastOrNull(),
            totalChapterEntries = library.sumOf { it.chapters.size },
            downloadedChapterEntries = library.sumOf { story -> story.chapters.count { it.downloaded } },
            tabs =
                tabs
                    .sortedBy { it.order }
                    .map { tab ->
                        DevLibraryTabReport(
                            id = tab.id,
                            name = tab.name,
                            order = tab.order,
                            storyCount = storiesPerTab[tab.id] ?: 0,
                        )
                    },
            untabbedStories = storiesPerTab[null] ?: 0,
            storageIssues =
                storageIssues.map { issue ->
                    DevStorageIssueReport(
                        document = issue.document,
                        kind = issue.kind.name,
                        detail = issue.detail,
                        recoveredStoryCount = issue.recoveredStoryCount,
                    )
                },
        )
    }

    fun toJson(report: DevLibraryReport): String = gson.toJson(report)
}
