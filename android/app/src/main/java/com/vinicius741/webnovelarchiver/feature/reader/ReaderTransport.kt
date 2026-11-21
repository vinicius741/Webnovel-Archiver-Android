package com.vinicius741.webnovelarchiver.feature.reader

import com.vinicius741.webnovelarchiver.domain.model.TtsSession
import com.vinicius741.webnovelarchiver.feature.reader.ReaderContentRenderer.ReaderDocumentColors
import com.vinicius741.webnovelarchiver.tts.TtsPlaybackSnapshot
import com.vinicius741.webnovelarchiver.tts.TtsPlaybackState
import com.vinicius741.webnovelarchiver.ui.ThemeManager

/** The v3 reading page: a warm near-black sheet with soft ivory text and the gold highlight. */
internal fun readerDocumentColors(): ReaderDocumentColors =
    ReaderDocumentColors(
        background = READER_PAGE,
        foreground = READER_TEXT,
        ttsHighlight = ThemeManager.colors.primary.toCssHex(),
    )

/** The reader's colors for each "Pure black" setting (the stored dark-reader preference). */
internal fun readerDocumentPalette(): ReaderDocumentPalette {
    val normal = readerDocumentColors()
    return ReaderDocumentPalette(normal = normal, forcedDark = normal.copy(background = PURE_BLACK))
}

private const val READER_PAGE = "#13110D"
private const val READER_TEXT = "#E6DED0"
private const val PURE_BLACK = "#000000"

private fun Int.toCssHex(): String = "#%06X".format(this and 0xFFFFFF)

internal fun cssColorToInt(cssColor: String): Int = android.graphics.Color.parseColor(cssColor)

internal fun currentReaderSnapshot(
    session: TtsSession?,
    storyId: String,
    chapterId: String,
    chunkCount: Int,
): TtsPlaybackSnapshot? {
    session ?: return null
    if (session.storyId != storyId || session.chapterId != chapterId) return null
    return TtsPlaybackState.snapshotForSession(
        session = session,
        totalChunks = chunkCount,
        isPlaying = !session.isPaused,
    )
}
