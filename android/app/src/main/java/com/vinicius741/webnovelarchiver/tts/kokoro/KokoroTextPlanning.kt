package com.vinicius741.webnovelarchiver.tts.kokoro

/**
 * Text as the Kokoro model should receive it. sherpa-onnx speaks whatever follows a sentence's final
 * `.`, `!` or `?` as one more sentence, so the closing quote of `“Go home.”` became about 0.2 s of
 * noise that ended at full volume: an audible glitch and a hard cut into the next pause. Kokoro also
 * voices `*` (about 0.5 s each), common around thoughts and sound effects in web novels. Marks inside
 * a sentence are left alone; they render cleanly.
 */
internal object KokoroTextPlanning {
    /**
     * Part of the Kokoro cache key: bump it whenever [spokenText] changes, so clips rendered from the
     * old text are not reused.
     */
    const val VERSION = 2

    private const val TERMINATORS = ".!?…"
    private const val CLOSERS = "\"'“”‘’«»‹›「」『』()[]{}【】〈〉《》_~"
    private val WHITESPACE = Regex("""\s+""")

    fun spokenText(text: String): String {
        val cleaned = text.replace('*', ' ').replace(WHITESPACE, " ").trim()
        var tailStart = cleaned.length
        while (tailStart > 0 && cleaned[tailStart - 1].let { it in TERMINATORS || it in CLOSERS || it == ' ' }) tailStart--
        val tail = cleaned.substring(tailStart)
        // A tail without a terminator, like the quote of `“Nobody moved”`, renders cleanly.
        if (tail.none { it in TERMINATORS }) return cleaned
        return cleaned.substring(0, tailStart) + tail.filter { it in TERMINATORS }
    }
}
