package com.vinicius741.webnovelarchiver.feature.reader

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.vinicius741.webnovelarchiver.BuildConfig
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.data.diagnostics.ActivityCategory
import com.vinicius741.webnovelarchiver.data.diagnostics.ActivityLog
import com.vinicius741.webnovelarchiver.data.repository.getTtsStoryPosition
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.feature.details.showDetails
import com.vinicius741.webnovelarchiver.feature.narration.narration
import com.vinicius741.webnovelarchiver.feature.narration.playIfGenerated
import com.vinicius741.webnovelarchiver.feature.player.PlayerProgressLine
import com.vinicius741.webnovelarchiver.feature.player.nextRatePreset
import com.vinicius741.webnovelarchiver.feature.player.playbackFraction
import com.vinicius741.webnovelarchiver.feature.player.playbackTimeLeft
import com.vinicius741.webnovelarchiver.feature.player.rateLabel
import com.vinicius741.webnovelarchiver.feature.player.showPlayer
import com.vinicius741.webnovelarchiver.feature.settings.showTtsSettings
import com.vinicius741.webnovelarchiver.feature.story.navigateChapter
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.perf.PerfInstrumentation
import com.vinicius741.webnovelarchiver.platform.WebViewSafety
import com.vinicius741.webnovelarchiver.source.sanitizeTitle
import com.vinicius741.webnovelarchiver.tts.TtsForegroundService
import com.vinicius741.webnovelarchiver.tts.TtsPlaybackSnapshot
import com.vinicius741.webnovelarchiver.tts.TtsPlaybackState
import com.vinicius741.webnovelarchiver.tts.TtsSessionPlanning
import com.vinicius741.webnovelarchiver.ui.AppBarAction
import com.vinicius741.webnovelarchiver.ui.BottomSheetHandle
import com.vinicius741.webnovelarchiver.ui.MaxWidthFrameLayout
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.copyToClipboard
import com.vinicius741.webnovelarchiver.ui.currentScreenLayout
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.iconButton
import com.vinicius741.webnovelarchiver.ui.layout.ScreenLayoutPlanning
import com.vinicius741.webnovelarchiver.ui.layout.readerSidePadding
import com.vinicius741.webnovelarchiver.ui.makeSheetRow
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.ripple
import com.vinicius741.webnovelarchiver.ui.roundCorners
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.selectableRipple
import com.vinicius741.webnovelarchiver.ui.showBottomSheet
import com.vinicius741.webnovelarchiver.ui.showReaderSettingsPanel
import com.vinicius741.webnovelarchiver.ui.strokeBg
import com.vinicius741.webnovelarchiver.ui.tintedIcon
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** What the reader's docked player does; the screen wires each to the TTS service. */
internal class ReaderDockActions(
    val playPause: () -> Unit,
    val back15: () -> Unit,
    val cycleRate: () -> Unit,
    val openPlayer: () -> Unit,
    val close: () -> Unit,
)

/**
 * The player the Reader docks in place of its chapter navigation while this chapter is read aloud
 * (v3): a progress line on top, a gold play/pause, "Sentence N of M" and the time left (opens Now
 * Playing), back 15 seconds, the speed chip, expand and close. Patched in place by [applySnapshot].
 */
internal class ReaderPlayerDock(
    context: Context,
    actions: ReaderDockActions,
) {
    private val colors = ThemeManager.colors
    private val line = PlayerProgressLine(context)
    private val play: ImageView
    private val title: TextView
    private val subtitle: TextView
    private val rate: TextView

    val view: FrameLayout =
        FrameLayout(context).apply {
            val radius = context.dp(RADIUS_DP).toFloat()
            background = strokeBg(DOCK_SURFACE, radius, colors.primary and BORDER_ALPHA_MASK, context.dp(1).coerceAtLeast(1))
            roundCorners(RADIUS_DP.toFloat())
            elevation = context.dp(Spacing.SM).toFloat()
        }

    init {
        val row =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(context.dp(Spacing.SM), 0, context.dp(Spacing.XS), 0)
            }
        val playRadius = context.dp(PLAY_RADIUS_DP).toFloat()
        play =
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                background = ripple(roundedBg(colors.primary, playRadius), playRadius, colors.onPrimary)
                isClickable = true
                isFocusable = true
                setOnClickListener { actions.playPause() }
            }
        row.addView(play, LinearLayout.LayoutParams(context.dp(PLAY_SIZE_DP), context.dp(PLAY_SIZE_DP)))
        title =
            makeText(context, "", Type.TITLE_SMALL, colors.onSurface).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
        subtitle =
            makeText(context, "", Type.BODY_SMALL, colors.onSurfaceVariant).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
        val texts =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(context.dp(Spacing.SM + 2), 0, context.dp(Spacing.XS), 0)
                isClickable = true
                isFocusable = true
                background = selectableRipple(colors.onSurface)
                contentDescription = "Open player"
                setOnClickListener { actions.openPlayer() }
                addView(title)
                addView(subtitle)
            }
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        row.addView(context.iconButton(R.drawable.wna_replay, "Back 15 seconds", colors.onSurfaceVariant, onClick = actions.back15))
        rate =
            makeText(context, "", Type.LABEL_MEDIUM, colors.onSurface).apply {
                gravity = Gravity.CENTER
                val chipRadius = context.dp(RATE_HEIGHT_DP).toFloat() / 2
                background = ripple(roundedBg(colors.elevation4, chipRadius), chipRadius, colors.onSurface)
                setPadding(context.dp(Spacing.SM + 1), 0, context.dp(Spacing.SM + 1), 0)
                isClickable = true
                isFocusable = true
                setOnClickListener { actions.cycleRate() }
            }
        row.addView(
            rate,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, context.dp(RATE_HEIGHT_DP)).apply {
                marginStart = context.dp(2)
                marginEnd = context.dp(2)
            },
        )
        row.addView(context.iconButton(R.drawable.wna_chevron_up, "Open player", colors.onSurfaceVariant, onClick = actions.openPlayer))
        row.addView(context.iconButton(R.drawable.wna_close, "Close player", colors.onSurfaceVariant, onClick = actions.close))
        view.addView(row, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, context.dp(HEIGHT_DP)))
        view.addView(line, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, context.dp(LINE_DP), Gravity.TOP))
    }

    fun applySnapshot(snapshot: TtsPlaybackSnapshot) {
        val paused = snapshot.isPaused
        play.setImageDrawable(view.context.tintedIcon(if (paused) R.drawable.wna_play else R.drawable.wna_pause, colors.onPrimary))
        play.contentDescription = if (paused) "Play" else "Pause"
        title.text =
            if (snapshot.totalChunks > 0) {
                "Sentence ${(snapshot.chunkIndex + 1).coerceAtMost(snapshot.totalChunks)} of ${snapshot.totalChunks}"
            } else {
                snapshot.title
            }
        subtitle.text = playbackTimeLeft(snapshot) ?: if (paused) "Paused" else "Loading…"
        rate.text = rateLabel(snapshot.rate)
        rate.contentDescription = "Speed ${rateLabel(snapshot.rate)}. Tap to change"
        line.fraction = playbackFraction(snapshot)
    }

    private companion object {
        const val HEIGHT_DP = 64
        const val RADIUS_DP = 20
        const val PLAY_SIZE_DP = 46
        const val PLAY_RADIUS_DP = 16
        const val RATE_HEIGHT_DP = 30
        const val LINE_DP = 3
        const val DOCK_SURFACE = 0xFF2C261B.toInt()
        const val BORDER_ALPHA_MASK = 0x33FFFFFF
    }
}

/** Docks a bottom bar inside the reader with the v3 floating margins, centered on wide screens. */
internal fun LinearLayout.bottomDock(child: View): View =
    MaxWidthFrameLayout(context).apply {
        maxContentWidthDp = DOCK_MAX_WIDTH_DP
        clipToPadding = false
        setPadding(context.dp(Spacing.SM), context.dp(Spacing.XS), context.dp(Spacing.SM), context.dp(Spacing.SM))
        addView(
            child,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL,
            ),
        )
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

/** The gold "Back to current sentence" pill shown while the reader has scrolled away from it. */
internal fun ScreenHost.followPill(onClick: () -> Unit): View {
    val colors = ThemeManager.colors
    val radius = dp(PILL_HEIGHT_DP).toFloat() / 2
    return makeText(app, "Back to current sentence", Type.LABEL_LARGE, colors.onPrimary).apply {
        gravity = Gravity.CENTER_VERTICAL
        setCompoundDrawablesRelativeWithIntrinsicBounds(
            app.tintedIcon(R.drawable.wna_my_location, colors.onPrimary)?.apply {
                setBounds(0, 0, dp(18), dp(18))
            },
            null,
            null,
            null,
        )
        compoundDrawablePadding = dp(6)
        setPadding(dp(Spacing.LG), 0, dp(Spacing.LG), 0)
        background = ripple(roundedBg(colors.primary, radius), radius, colors.onPrimary)
        elevation = dp(Spacing.SM).toFloat()
        isClickable = true
        isFocusable = true
        visibility = View.GONE
        setOnClickListener { onClick() }
    }
}

internal const val PILL_HEIGHT_DP = 40
internal const val DOCK_MAX_WIDTH_DP = 680
