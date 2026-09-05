package com.vinicius741.webnovelarchiver.data.storage

import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.settings.PreferenceNormalization

/**
 * Backfills stable provider identity without expanding the core JSON storage implementation.
 *
 * R20: the library variant runs in memory on the startup pass's already-loaded list and returns
 * the ids whose documents changed, so the caller persists only those — no second whole-library
 * read and no wholesale [AppStorage.saveLibrary] rewrite when just a few stories were missing ids.
 */
internal fun AppStorage.migrateSourceIdentities(
    library: List<Story>,
    sourceIdForUrl: (String) -> String?,
    sourceIdForSettingKey: (String) -> String?,
): Set<String> {
    val changedIds = mutableSetOf<String>()
    library.forEach { story ->
        if (story.sourceId.isNullOrBlank()) {
            sourceIdForUrl(story.sourceUrl)?.let { sourceId ->
                story.sourceId = sourceId
                changedIds.add(story.id)
            }
        }
    }
    migrateQueueAndSettingsSourceIdentities(sourceIdForUrl, sourceIdForSettingKey)
    return changedIds
}

/** Queue and source-download-settings identity backfill; both are small single documents. */
internal fun AppStorage.migrateQueueAndSettingsSourceIdentities(
    sourceIdForUrl: (String) -> String?,
    sourceIdForSettingKey: (String) -> String?,
) {
    val jobs = getQueue()
    var queueChanged = false
    jobs.forEach { job ->
        if (job.sourceId.isNullOrBlank()) {
            sourceIdForUrl(job.chapter.url)?.let { sourceId ->
                job.sourceId = sourceId
                queueChanged = true
            }
        }
    }
    if (queueChanged) saveQueue(jobs)

    val currentSettings = getSourceDownloadSettings()
    val migratedSettings =
        PreferenceNormalization.migrateSourceDownloadSettingKeys(currentSettings, sourceIdForSettingKey)
    if (migratedSettings != currentSettings) saveSourceDownloadSettings(migratedSettings)
}
