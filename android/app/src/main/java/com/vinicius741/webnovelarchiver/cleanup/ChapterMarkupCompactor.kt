package com.vinicius741.webnovelarchiver.cleanup

/**
 * A source's markup that it adds to every stored chapter and that nothing in the app reads (the
 * reader, TTS, EPUB and cleanup all ignore it). Dropping it only shrinks chapter files and backups.
 */
fun interface ChapterMarkupCompactor {
    /** [html] without the noise. Returns the same instance when there is nothing to drop. */
    fun compact(html: String): String
}
