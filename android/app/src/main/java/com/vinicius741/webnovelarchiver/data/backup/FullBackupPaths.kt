package com.vinicius741.webnovelarchiver.data.backup

import com.vinicius741.webnovelarchiver.domain.archive.PercentEncoding

object FullBackupPaths {
    fun chapterPath(
        storyId: String,
        chapterId: String,
        chapterIndex: Int,
    ): String = "novels/${encodeURIComponent(storyId)}/${chapterIndex.toString().padStart(4, '0')}_${encodeURIComponent(chapterId)}.html"

    /** In-Zip path of a story's trend-history file inside a full backup. Mirrors the on-disk
     *  `metrics/<safeName(id)>.json` layout so restore can copy the whole `metrics/` tree verbatim. */
    fun metricPath(storyId: String): String = "metrics/${encodeURIComponent(storyId)}.json"

    /**
     * In-Zip path for a cover the export re-encodes as WebP: the story's own `aiCoverPath` with a
     * `.webp` extension, so the restored file's name matches its content. Null keeps the original
     * path and bytes: the cover is already `.webp`, or another cover owns that path (restore
     * rejects duplicate cover paths).
     */
    fun compactCoverPath(
        path: String,
        takenPaths: Set<String>,
    ): String? {
        val name = path.substringAfterLast('/')
        val stem = if ('.' in name) path.dropLast(name.length - name.lastIndexOf('.')) else path
        return "$stem.webp".takeIf { it != path && it !in takenPaths }
    }

    fun encodeURIComponent(value: String): String = PercentEncoding.encodeURIComponent(value)
}
