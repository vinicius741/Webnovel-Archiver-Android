package com.vinicius741.webnovelarchiver.domain.archive

/** JavaScript-compatible URI-component encoding for portable archive path segments. */
object PercentEncoding {
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
