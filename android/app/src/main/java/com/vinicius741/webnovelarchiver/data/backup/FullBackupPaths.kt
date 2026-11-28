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

    /** In-Zip path of a story's [ChapterPack] in a version-3 full backup. */
    fun chapterPackPath(storyId: String): String = "$CHAPTER_PACK_DIRECTORY/${encodeURIComponent(storyId)}$CHAPTER_PACK_EXTENSION"

    fun isChapterPackPath(path: String): Boolean = path.startsWith("$CHAPTER_PACK_DIRECTORY/") && path.endsWith(CHAPTER_PACK_EXTENSION)

    /**
     * The `novels/<story>` directory a pack's chapter files extract into, taken from the pack's own
     * entry name, so a pack can never write into another story's directory.
     */
    fun chapterPackStoryDirectory(packPath: String): String =
        "novels/" + packPath.removePrefix("$CHAPTER_PACK_DIRECTORY/").removeSuffix(CHAPTER_PACK_EXTENSION)

    const val CHAPTER_PACK_DIRECTORY = "chapter_packs"
    const val CHAPTER_PACK_EXTENSION = ".zst"

    fun encodeURIComponent(value: String): String = PercentEncoding.encodeURIComponent(value)
}
