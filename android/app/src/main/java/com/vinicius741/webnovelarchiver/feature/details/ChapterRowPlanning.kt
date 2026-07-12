package com.vinicius741.webnovelarchiver.feature.details

import com.vinicius741.webnovelarchiver.domain.model.DownloadJobStatus
import com.vinicius741.webnovelarchiver.source.sanitizeTitle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pure display helpers for chapter rows on the library details list (and chapter selection).
 * Keeps index/title/subtitle formatting out of the adapter so it can be unit-tested and reused.
 */
object ChapterRowPlanning {
    private val downloadDateFormatter =
        DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)

    /** 1-based chapter number as a bare digit string (no trailing period). */
    fun indexLabel(zeroBasedIndex: Int): String = (zeroBasedIndex + 1).coerceAtLeast(1).toString()

    /** Sanitized chapter title only — never prefixed with the library index. */
    fun displayTitle(rawTitle: String?): String = sanitizeTitle(rawTitle.orEmpty())

    /**
     * Secondary line under the title.
     * Live queue status wins; otherwise a compact download date when known, or a quiet offline
     * cue for legacy downloads that predate [com.vinicius741.webnovelarchiver.domain.model.Chapter.downloadedAt].
     */
    fun subtitle(
        liveStatus: DownloadJobStatus?,
        downloaded: Boolean,
        downloadedAt: Long?,
    ): String? =
        when (liveStatus) {
            DownloadJobStatus.Downloading -> "Downloading…"
            DownloadJobStatus.Pending -> "Queued"
            DownloadJobStatus.Failed -> "Download failed"
            else ->
                when {
                    !downloaded -> null
                    downloadedAt != null -> "Downloaded ${formatDownloadDate(downloadedAt)}"
                    else -> "Available offline"
                }
        }

    /** Compact second line that distinguishes the list position from the source's chapter title. */
    fun metadataLabel(
        zeroBasedIndex: Int,
        liveStatus: DownloadJobStatus?,
        downloaded: Boolean,
        downloadedAt: Long?,
    ): String =
        buildString {
            append("Index ")
            append(indexLabel(zeroBasedIndex))
            subtitle(liveStatus, downloaded, downloadedAt)?.let {
                append("  •  ")
                append(it)
            }
        }

    fun formatDownloadDate(timestampMillis: Long): String =
        downloadDateFormatter.format(
            Instant.ofEpochMilli(timestampMillis).atZone(ZoneId.systemDefault()),
        )

    /**
     * How many digit columns the index field needs so a list of N chapters lines up.
     * Uses the largest 1-based index that will appear ([chapterCount]).
     */
    fun indexDigitCount(chapterCount: Int): Int = maxOf(1, chapterCount.coerceAtLeast(1).toString().length)
}
