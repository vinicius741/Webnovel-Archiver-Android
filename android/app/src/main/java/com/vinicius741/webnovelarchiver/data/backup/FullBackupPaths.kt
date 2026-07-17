package com.vinicius741.webnovelarchiver.data.backup

object FullBackupPaths {
    fun chapterPath(
        storyId: String,
        chapterId: String,
        chapterIndex: Int,
    ): String = "novels/${encodeURIComponent(storyId)}/${chapterIndex.toString().padStart(4, '0')}_${encodeURIComponent(chapterId)}.html"

    /** In-Zip path of a story's trend-history file inside a full backup. Mirrors the on-disk
     *  `metrics/<safeName(id)>.json` layout so restore can copy the whole `metrics/` tree verbatim. */
    fun metricPath(storyId: String): String = "metrics/${encodeURIComponent(storyId)}.json"

    fun encodeURIComponent(value: String): String =
        buildString {
            value.encodeToByteArray().forEach { byte ->
                val unsigned = byte.toInt() and 0xff
                val char = unsigned.toChar()
                if (char in UNESCAPED_CHARACTERS) {
                    append(char)
                } else {
                    append('%')
                    append(unsigned.toString(16).uppercase().padStart(2, '0'))
                }
            }
        }

    private val UNESCAPED_CHARACTERS: Set<Char> =
        (('A'..'Z') + ('a'..'z') + ('0'..'9') + listOf('-', '_', '.', '!', '~', '*', '\'', '(', ')')).toSet()
}
