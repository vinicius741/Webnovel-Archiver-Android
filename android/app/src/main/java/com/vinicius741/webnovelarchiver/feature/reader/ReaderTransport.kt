package com.vinicius741.webnovelarchiver.feature.reader

import android.view.Gravity
import android.webkit.WebView
import android.widget.ImageView
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.model.TtsSession
import com.vinicius741.webnovelarchiver.feature.reader.ReaderContentRenderer.ReaderDocumentColors
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.tts.TtsPlaybackSnapshot
import com.vinicius741.webnovelarchiver.tts.TtsPlaybackState
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.iconButton

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

internal fun applyHighlight(
    reader: WebView,
    chunkIndex: Int?,
    totalChunks: Int,
) {
    if (totalChunks <= 0 && chunkIndex == null) {
        reader.evaluateJavascript("if(window.WnaTts){WnaTts.setActive(null);}", null)
        return
    }
    val safeIndex = chunkIndex ?: return
    reader.evaluateJavascript("if(window.WnaTts){WnaTts.setActive($safeIndex);}", null)
}

internal fun ScreenHost.readerTtsTransport(
    snapshot: TtsPlaybackSnapshot?,
    onPlayPause: (ImageView) -> Unit,
    onPrev: () -> Unit,
    onPlayPauseTap: () -> Unit,
    onNext: () -> Unit,
    onStop: () -> Unit,
): LinearLayout {
    val colors = ThemeManager.colors
    val bar =
        LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(colors.elevation1)
            setPadding(dp(Spacing.MD), dp(Spacing.XS), dp(Spacing.MD), dp(Spacing.XS))
        }

    fun transportIcon(
        desc: String,
        icon: Int,
        action: () -> Unit,
    ) = app.iconButton(icon, desc, colors.primary, onClick = action).apply {
        (layoutParams as LinearLayout.LayoutParams).apply {
            marginStart = dp(Spacing.XS)
            marginEnd = dp(Spacing.XS)
        }
    }
    bar.addView(transportIcon("Previous sentence", R.drawable.wna_skip_prev, onPrev))
    val isPaused = snapshot?.isPaused != false
    val playPause =
        transportIcon(
            if (isPaused) "Play TTS" else "Pause TTS",
            if (isPaused) R.drawable.wna_play else R.drawable.wna_pause,
            onPlayPauseTap,
        )
    onPlayPause(playPause)
    bar.addView(playPause)
    bar.addView(transportIcon("Next sentence", R.drawable.wna_skip_next, onNext))
    bar.addView(
        android.view.View(app).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        },
    )
    bar.addView(transportIcon("Stop TTS", R.drawable.wna_stop, onStop))
    return bar
}
