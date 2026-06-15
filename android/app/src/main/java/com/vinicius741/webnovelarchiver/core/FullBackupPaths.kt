package com.vinicius741.webnovelarchiver.core

object FullBackupPaths {
    fun chapterPath(storyId: String, chapterId: String, chapterIndex: Int): String =
        "novels/${encodeURIComponent(storyId)}/${chapterIndex.toString().padStart(4, '0')}_${encodeURIComponent(chapterId)}.html"

    fun encodeURIComponent(value: String): String = buildString {
        value.encodeToByteArray().forEach { byte ->
            val unsigned = byte.toInt() and 0xff
            val char = unsigned.toChar()
            if (
                char in 'A'..'Z' ||
                char in 'a'..'z' ||
                char in '0'..'9' ||
                char == '-' ||
                char == '_' ||
                char == '.' ||
                char == '!' ||
                char == '~' ||
                char == '*' ||
                char == '\'' ||
                char == '(' ||
                char == ')'
            ) {
                append(char)
            } else {
                append('%')
                append(unsigned.toString(16).uppercase().padStart(2, '0'))
            }
        }
    }
}
