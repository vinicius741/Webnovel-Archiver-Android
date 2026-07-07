package com.vinicius741.webnovelarchiver.domain.story

import com.vinicius741.webnovelarchiver.domain.model.PublicationStatus
import com.vinicius741.webnovelarchiver.domain.model.Story

object PublicationStatusPlanning {
    private const val DAY_MS = 24L * 60L * 60L * 1000L
    const val OUTDATED_AFTER_MS = 7L * DAY_MS
    const val HIATUS_AFTER_MS = 30L * DAY_MS

    fun afterSync(
        sourceStatus: PublicationStatus,
        latestChapterPublishedAt: Long?,
        syncedAt: Long,
    ): PublicationStatus {
        if (sourceStatus == PublicationStatus.completed) return PublicationStatus.completed
        if (sourceStatus == PublicationStatus.hiatus) return PublicationStatus.hiatus
        if (latestChapterPublishedAt != null && syncedAt - latestChapterPublishedAt > HIATUS_AFTER_MS) {
            return PublicationStatus.hiatus
        }
        return sourceStatus
    }

    fun effectiveStatus(
        story: Story,
        now: Long = System.currentTimeMillis(),
    ): PublicationStatus {
        val base = story.publicationStatus
        if (story.isArchived == true || base == PublicationStatus.completed || base == PublicationStatus.hiatus) {
            return base
        }
        val lastChapterSyncAt = story.lastChapterSyncAt ?: story.lastUpdated ?: return base
        return if (now - lastChapterSyncAt > OUTDATED_AFTER_MS) PublicationStatus.outdated else base
    }
}
