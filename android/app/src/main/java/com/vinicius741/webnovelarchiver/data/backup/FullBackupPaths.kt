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

    fun encodeURIComponent(value: String): String = PercentEncoding.encodeURIComponent(value)
}
