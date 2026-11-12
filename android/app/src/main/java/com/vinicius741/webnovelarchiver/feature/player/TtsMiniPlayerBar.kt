package com.vinicius741.webnovelarchiver.feature.player

import android.content.Context
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
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
import com.vinicius741.webnovelarchiver.ui.roundCorners
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.selectableRipple
import com.vinicius741.webnovelarchiver.ui.strokeBg
import com.vinicius741.webnovelarchiver.ui.tintedIcon

/** The playing chapter's progress, from audio time when known, else from sentences. */
internal fun playbackFraction(snapshot: TtsPlaybackSnapshot?): Float {
    snapshot ?: return 0f
    if (snapshot.durationMs > 0L) {
        return snapshot.positionAt(SystemClock.elapsedRealtime()).toFloat() / snapshot.durationMs
    }
    if (snapshot.totalChunks <= 0) return 0f
    return (snapshot.chunkIndex + 1).toFloat() / snapshot.totalChunks
}

/** "12 min left" at the current speed, or null while the length is unknown. */
internal fun playbackTimeLeft(snapshot: TtsPlaybackSnapshot?): String? =
    snapshot?.takeIf { it.durationMs > 0L }?.let {
        TtsTimeFormat.timeLeft(it.positionAt(SystemClock.elapsedRealtime()), it.durationMs, it.rate)
    }

/**
 * The v3 mini player: a rounded card with the cover, the chapter, "N min left · story", a gold
 * play/pause circle, close, and a thin progress line along the bottom. The app shell docks one copy
 * in its bottom shelf; [applySnapshot] patches it in place from the playback flow. [coverFor]
 * builds the cover thumbnail for a story id (null leaves the slot empty).
 */
internal class TtsMiniPlayerBar(
    context: Context,
    onPlayPause: () -> Unit,
    onOpenPlayer: () -> Unit,
    onClose: () -> Unit,
    private val coverFor: ((String) -> View?)? = null,
) {
    private val colors = ThemeManager.colors
    private val coverSlot: FrameLayout
    private val playPauseButton: ImageView
    private val chapterText: TextView
    private val subtitleText: TextView
    private val progressLine = PlayerProgressLine(context)
    private var coverStoryId: String? = null

    val view: FrameLayout =
        FrameLayout(context).apply {
            val radius = context.dp(RADIUS_DP).toFloat()
            background = strokeBg(MINI_SURFACE, radius, colors.primary and BORDER_ALPHA_MASK, context.dp(1).coerceAtLeast(1))
            roundCorners(RADIUS_DP.toFloat())
            elevation = context.dp(Spacing.XS).toFloat()
            contentDescription = "Now playing"
        }

    init {
        val row =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(context.dp(Spacing.SM), 0, context.dp(Spacing.XS), 0)
            }
        val hit =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                background = selectableRipple(colors.onSurface)
                contentDescription = "Open player"
                setOnClickListener { onOpenPlayer() }
            }
        coverSlot = FrameLayout(context).apply { roundCorners(COVER_RADIUS_DP) }
        hit.addView(
            coverSlot,
            LinearLayout.LayoutParams(context.dp(COVER_WIDTH_DP), context.dp(COVER_HEIGHT_DP)).apply { marginEnd = context.dp(Spacing.MD) },
        )
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        chapterText =
            makeText(context, "", Type.TITLE_SMALL, colors.onSurface).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
        subtitleText =
            makeText(context, "", Type.BODY_SMALL, colors.onSurfaceVariant).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
        texts.addView(chapterText)
        texts.addView(subtitleText)
        hit.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(hit, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))

        val playSize = context.dp(PLAY_SIZE_DP)
        playPauseButton =
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                background = ripple(roundedBg(colors.primary, playSize / 2f), playSize / 2f, colors.onPrimary)
                isClickable = true
                isFocusable = true
                setOnClickListener { onPlayPause() }
            }
        row.addView(
            playPauseButton,
            LinearLayout.LayoutParams(playSize, playSize).apply {
                marginStart = context.dp(Spacing.SM)
                marginEnd = context.dp(Spacing.XS)
            },
        )
        row.addView(context.iconButton(R.drawable.wna_close, "Close player", colors.onSurfaceVariant, onClick = onClose))
        view.addView(row, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, context.dp(HEIGHT_DP)))
        view.addView(
            progressLine,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, context.dp(LINE_HEIGHT_DP), Gravity.BOTTOM),
        )
    }

    fun applySnapshot(snapshot: TtsPlaybackSnapshot?) {
        val isPaused = snapshot?.isPaused != false
        playPauseButton.setImageDrawable(
            view.context.tintedIcon(if (isPaused) R.drawable.wna_play else R.drawable.wna_pause, colors.onPrimary),
        )
        playPauseButton.contentDescription = if (isPaused) "Play" else "Pause"
        chapterText.text = snapshot?.title.orEmpty()
        subtitleText.text =
            listOfNotNull(playbackTimeLeft(snapshot), snapshot?.storyTitle?.takeIf { it.isNotBlank() }).joinToString(" · ")
        progressLine.fraction = playbackFraction(snapshot)
        bindCover(snapshot?.storyId)
    }

    private fun bindCover(storyId: String?) {
        if (storyId == coverStoryId) return
        coverStoryId = storyId
        coverSlot.removeAllViews()
        val cover = storyId?.let { coverFor?.invoke(it) }
        coverSlot.visibility = if (cover == null) View.GONE else View.VISIBLE
        cover?.let {
            it.setOnClickListener(null)
            it.isClickable = false
            coverSlot.addView(it, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
    }

    private companion object {
        const val HEIGHT_DP = 62
        const val RADIUS_DP = 18
        const val COVER_WIDTH_DP = 34
        const val COVER_HEIGHT_DP = 50
        const val COVER_RADIUS_DP = 6f
        const val PLAY_SIZE_DP = 44
        const val LINE_HEIGHT_DP = 3
        const val MINI_SURFACE = 0xFF2A251C.toInt()
        const val BORDER_ALPHA_MASK = 0x29FFFFFF
    }
}
