package com.vinicius741.webnovelarchiver.data.storage

import com.vinicius741.webnovelarchiver.domain.settings.PreferenceNormalization

/** Backfills stable provider identity without expanding the core JSON storage implementation. */
internal fun AppStorage.migrateSourceIdentities(
    sourceIdForUrl: (String) -> String?,
    sourceIdForSettingKey: (String) -> String?,
) {
    val library = getLibrary()
    var libraryChanged = false
    library.forEach { story ->
        if (story.sourceId.isNullOrBlank()) {
            sourceIdForUrl(story.sourceUrl)?.let { sourceId ->
                story.sourceId = sourceId
                libraryChanged = true
            }
        }
    }
    if (libraryChanged) saveLibrary(library)

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
