package com.vinicius741.webnovelarchiver.tts.kokoro

import java.text.Normalizer

/**
 * Text as the Kokoro model should receive it. sherpa-onnx speaks whatever follows a sentence's final
 * `.`, `!` or `?` as one more sentence, so the closing quote of `“Go home.”` became about 0.2 s of
 * noise that ended at full volume: an audible glitch and a hard cut into the next pause. Kokoro also
 * voices `*` (about 0.5 s each), common around thoughts and sound effects in web novels. Marks inside
 * a sentence are left alone; they render cleanly. Accents go too: see [LatinAccents].
 */
internal object KokoroTextPlanning {
    /**
     * Part of the Kokoro cache key: bump it whenever [spokenText] changes, so clips rendered from the
     * old text are not reused.
     */
    const val VERSION = 3

    private const val TERMINATORS = ".!?…"
    private const val CLOSERS = "\"'“”‘’«»‹›「」『』()[]{}【】〈〉《》_~"
    private val WHITESPACE = Regex("""\s+""")

    fun spokenText(text: String): String {
        val cleaned =
            LatinAccents
                .fold(text)
                .replace('*', ' ')
                .replace(WHITESPACE, " ")
                .trim()
        var tailStart = cleaned.length
        while (tailStart > 0 && cleaned[tailStart - 1].let { it in TERMINATORS || it in CLOSERS || it == ' ' }) tailStart--
        val tail = cleaned.substring(tailStart)
        // A tail without a terminator, like the quote of `“Nobody moved”`, renders cleanly.
        if (tail.none { it in TERMINATORS }) return cleaned
        return cleaned.substring(0, tailStart) + tail.filter { it in TERMINATORS }
    }
}

/**
 * Latin letters with accents as their plain letters. The built-in English voices spelled letters such
 * as Vietnamese `ạ` and `ệ` out by code point, so `Phạm Diệp` came out as "Ph letter 1EA1 m". Plain
 * letters read as the name. Other scripts are left alone.
 */
internal object LatinAccents {
    private val LOOSE_MARKS = Regex("""(?<=[A-Za-z])\p{Mn}+""")
    private val MARKS = Regex("""\p{Mn}+""")

    fun fold(text: String): String {
        if (text.none { isAccented(it) || Character.getType(it) == Character.NON_SPACING_MARK.toInt() }) return text
        val letters = buildString(text.length) { text.forEach { append(if (isAccented(it)) plain(it) else it) } }
        return letters.replace(LOOSE_MARKS, "")
    }

    private fun isAccented(char: Char): Boolean = char.code in 0x00C0..0x024F || char.code in 0x1E00..0x1EFF

    private fun plain(char: Char): String =
        when (char) {
            'đ' -> "d"
            'Đ' -> "D"
            else -> Normalizer.normalize(char.toString(), Normalizer.Form.NFD).replace(MARKS, "").ifEmpty { char.toString() }
        }
}
