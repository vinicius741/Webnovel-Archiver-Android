package com.vinicius741.webnovelarchiver.feature.player

import android.content.Context
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.tts.TtsPlaybackSnapshot
import com.vinicius741.webnovelarchiver.tts.TtsTimeFormat
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.iconButton
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.ripple
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.tintedIcon
import kotlinx.coroutines.flow.collect

/**
 * The TTS bar: play/pause, chapter + story title, sentence progress. The app-level floating copy is
 * built once per activity; the Reader docks its own copy in place of its chapter navigation while
 * that chapter is being read aloud. [applySnapshot] patches it in place from the playback flow.
 */
internal class TtsMiniPlayerBar(
    context: Context,
    onPlayPause: () -> Unit,
    onOpenPlayer: () -> Unit,
    onClose: () -> Unit,
) {
    private val colors = ThemeManager.colors
    private val playPauseButton: ImageView
    private val chevronButton: ImageView
    private val closeButton: ImageView
    private val chapterText: TextView
    private val storyText: TextView
    private val progressTrack: ProgressBar

    val view =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background =
                ripple(roundedBg(colors.elevation2, context.dp(Spacing.LG).toFloat()), context.dp(Spacing.LG).toFloat(), colors.onSurface)
            elevation = context.dp(6).toFloat()
            isClickable = true
            isFocusable = true
            setOnClickListener { onOpenPlayer() }
        }

    init {
        val row =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(context.dp(Spacing.SM), context.dp(Spacing.XS + 2), context.dp(Spacing.SM), 0)
            }
        playPauseButton =
            context.iconButton(R.drawable.wna_play, "Play TTS", colors.primary, onClick = onPlayPause)
        row.addView(playPauseButton)

        val textColumn =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(context.dp(Spacing.XS), context.dp(Spacing.XS), context.dp(Spacing.XS), context.dp(Spacing.XS))
            }
        chapterText =
            makeText(context, "", Type.BODY_MEDIUM, colors.onSurface).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
        storyText =
            makeText(context, "", Type.BODY_SMALL, colors.onSurfaceVariant).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
        textColumn.addView(chapterText)
        textColumn.addView(storyText)
        row.addView(textColumn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        chevronButton = context.iconButton(R.drawable.wna_chevron_up, "Open player", colors.onSurfaceVariant, onClick = onOpenPlayer)
        row.addView(chevronButton)
        closeButton = context.iconButton(R.drawable.wna_close, "Close player", colors.onSurfaceVariant, onClick = onClose)
        row.addView(closeButton)
        view.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        progressTrack =
            ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                progressTintList =
                    android.content.res.ColorStateList
                        .valueOf(colors.primary)
                max = 0
                setPadding(context.dp(Spacing.SM), 0, context.dp(Spacing.SM), context.dp(Spacing.XS + 2))
            }
        view.addView(progressTrack, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dp(8)))
    }

    fun applySnapshot(snapshot: TtsPlaybackSnapshot?) {
        val isPaused = snapshot?.isPaused != false
        playPauseButton.setImageDrawable(
            view.context.tintedIcon(if (isPaused) R.drawable.wna_play else R.drawable.wna_pause, colors.primary),
        )
        playPauseButton.contentDescription = if (isPaused) "Play TTS" else "Pause TTS"
        chapterText.text = snapshot?.title ?: ""
        val timeLeft =
            snapshot?.takeIf { it.durationMs > 0L }?.let {
                TtsTimeFormat.timeLeft(it.positionAt(SystemClock.elapsedRealtime()), it.durationMs, it.rate)
            }
        storyText.text = listOfNotNull(timeLeft, snapshot?.storyTitle?.takeIf { it.isNotBlank() }).joinToString(" · ")
        progressTrack.max = snapshot?.totalChunks ?: 0
        progressTrack.progress = ((snapshot?.chunkIndex ?: -1) + 1).coerceIn(0, progressTrack.max)
    }
}
