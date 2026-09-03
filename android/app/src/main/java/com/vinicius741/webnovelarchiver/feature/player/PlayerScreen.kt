package com.vinicius741.webnovelarchiver.feature.player

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.tts.TtsForegroundService
import com.vinicius741.webnovelarchiver.tts.TtsPlaybackSnapshot
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.coverImage
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.elevate
import com.vinicius741.webnovelarchiver.ui.iconButton
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.ripple
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.tintedIcon
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Speed presets cycled by the player's speed chip. */
internal val RATE_PRESETS = floatArrayOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

internal fun nextRatePreset(current: Float): Float = RATE_PRESETS.firstOrNull { it > current + 0.01f } ?: RATE_PRESETS.first()

internal fun rateLabel(rate: Float): String {
    val text = if (rate == rate.toInt().toFloat()) rate.toInt().toString() else rate.toString()
    return "${text}x"
}

internal fun sleepTimerLabel(
    targetEpochMs: Long?,
    endOfChapter: Boolean,
): String =
    when {
        targetEpochMs != null -> {
            val remMin = maxOf(1, ((targetEpochMs - System.currentTimeMillis() + 59_999) / 60_000).toInt())
            "Timer: ${remMin}m"
        }
        endOfChapter -> "Timer: Ch. End"
        else -> "Timer: Off"
    }

/** Live-patched views inside the player screen (transport icon, progress, speed label, sleep timer). */
private class PlayerLiveViews(
    private val context: Context,
    private val playPauseButton: ImageView,
    private val progressLabel: TextView,
    private val seekBar: SeekBar,
    private val rateChip: LinearLayout,
    private val sleepTimerChip: LinearLayout,
) {
    private var currentRate: Float = 1f
    private var isUserSeeking: Boolean = false

    init {
        rateChip.setOnClickListener {
            TtsForegroundService.setRate(context, nextRatePreset(currentRate))
        }
        sleepTimerChip.setOnClickListener {
            val options = arrayOf("Off", "15 minutes", "30 minutes", "45 minutes", "60 minutes", "End of chapter")
            val values = intArrayOf(0, 15, 30, 45, 60, -1)
            android.app.AlertDialog
                .Builder(context)
                .setTitle("Sleep Timer")
                .setItems(options) { _, which ->
                    TtsForegroundService.setSleepTimer(context, values[which])
                }.setNegativeButton("Cancel", null)
                .show()
        }
        seekBar.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    sb: SeekBar?,
                    progress: Int,
                    fromUser: Boolean,
                ) {
                    if (fromUser) {
                        val max = sb?.max ?: 0
                        progressLabel.text =
                            if (max > 0) {
                                "Sentence ${progress.coerceIn(1, max)} / $max"
                            } else {
                                "Buffering"
                            }
                    }
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {
                    isUserSeeking = true
                }

                override fun onStopTrackingTouch(sb: SeekBar?) {
                    isUserSeeking = false
                    val targetChunk = ((sb?.progress ?: 1) - 1).coerceAtLeast(0)
                    TtsForegroundService.seekChunk(context, targetChunk)
                }
            },
        )
    }

    fun apply(state: TtsPlaybackSnapshot?) {
        currentRate = state?.rate ?: 1f
        val isPaused = state?.isPaused != false
        playPauseButton.setImageDrawable(
            context.tintedIcon(if (isPaused) R.drawable.wna_play else R.drawable.wna_pause, ThemeManager.colors.onPrimary),
        )
        playPauseButton.contentDescription = if (isPaused) "Play" else "Pause"
        if (!isUserSeeking) {
            progressLabel.text =
                if (state != null && state.totalChunks > 0) {
                    "Sentence ${(state.chunkIndex + 1).coerceIn(1, state.totalChunks)} / ${state.totalChunks}"
                } else {
                    "Buffering"
                }
            seekBar.max = state?.totalChunks ?: 0
            seekBar.progress = ((state?.chunkIndex ?: -1) + 1).coerceIn(0, seekBar.max)
        }
        rateChip.removeAllViews()
        rateChip.addView(makeText(context, rateLabel(currentRate), Type.LABEL_LARGE, ThemeManager.colors.primary))
        sleepTimerChip.removeAllViews()
        val timerText = sleepTimerLabel(state?.sleepTimerTargetEpochMs, state?.sleepTimerEndOfChapter == true)
        val timerColor =
            if (state?.sleepTimerTargetEpochMs != null || state?.sleepTimerEndOfChapter == true) {
                ThemeManager.colors.primary
            } else {
                ThemeManager.colors.onSurfaceVariant
            }
        sleepTimerChip.addView(makeText(context, timerText, Type.LABEL_LARGE, timerColor))
    }
}

/**
 * Podcast-style Now Playing screen. Rebuilds when playback moves to another chapter; patches the
 * transport controls in place for chunk/rate/play-state changes. Closes itself when playback ends.
 */
internal fun ScreenHost.showPlayer() {
    rerender = { showPlayer() }
    val initial = ttsEngine.playbackState.value.snapshot
    var liveViews: PlayerLiveViews? = null

    screen(
        route = AppRoute.Player,
        title = "Now Playing",
        onBack = { navigateBack() },
    ) {
        liveViews = buildPlayerBody(this, initial)
    }

    screenObserver =
        scope.launch {
            ttsEngine.playbackState.collect { update ->
                val state = update.snapshot
                if (state == null) {
                    if (update.isAuthoritative && navigator.current == AppRoute.Player) navigateBack()
                    return@collect
                }
                if (initial == null || initial.storyId != state.storyId || initial.chapterId != state.chapterId) {
                    showPlayer()
                } else {
                    liveViews?.apply(state)
                }
            }
        }
}

/** Builds the player body for [state]; returns the views that are patched live afterwards. */
private fun ScreenHost.buildPlayerBody(
    content: LinearLayout,
    state: TtsPlaybackSnapshot?,
): PlayerLiveViews? {
    if (state == null) {
        content.addView(
            makeText(app, "Nothing playing", Type.BODY_LARGE, ThemeManager.colors.onSurfaceVariant).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(Spacing.XL), 0, dp(Spacing.XL))
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        return null
    }
    val colors = ThemeManager.colors
    val story = repository.story(state.storyId)
    val chapterIndex = story?.chapters?.indexOfFirst { it.id == state.chapterId } ?: -1

    val coverHolder =
        LinearLayout(app).apply { gravity = Gravity.CENTER_HORIZONTAL }
    story?.let { coverHolder.addView(coverImage(it, 150, 225, tapToOpen = false)) }
    content.addView(coverHolder)

    content.addView(
        makeText(app, state.storyTitle.ifBlank { story?.title ?: "" }, Type.TITLE_MEDIUM, colors.onSurface).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(Spacing.MD), 0, 0)
        },
    )
    content.addView(
        makeText(app, state.title, Type.BODY_LARGE, colors.onSurfaceVariant).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(Spacing.XS), 0, 0)
        },
    )
    if (chapterIndex >= 0 && story != null) {
        content.addView(
            makeText(app, "Chapter ${chapterIndex + 1} / ${story.chapters.size}", Type.BODY_SMALL, colors.onSurfaceVariant).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(Spacing.XS), 0, 0)
            },
        )
    }

    val seekBar =
        SeekBar(app).apply {
            progressTintList = ColorStateList.valueOf(colors.primary)
            thumbTintList = ColorStateList.valueOf(colors.primary)
            setPadding(dp(Spacing.MD), dp(Spacing.MD), dp(Spacing.MD), 0)
        }
    content.addView(seekBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    val progressLabel = makeText(app, "", Type.BODY_SMALL, colors.onSurfaceVariant).apply { gravity = Gravity.CENTER }
    content.addView(progressLabel)

    fun transportIcon(
        desc: String,
        icon: Int,
        action: String,
    ) = app.iconButton(icon, desc, colors.primary) { TtsForegroundService.command(app, action) }

    val playPause =
        ImageView(app).apply {
            contentDescription = "Play"
            val size = dp(68)
            layoutParams =
                LinearLayout.LayoutParams(size, size).apply {
                    marginStart = dp(Spacing.SM)
                    marginEnd = dp(Spacing.SM)
                }
            val corner = (size / 2).toFloat()
            background = ripple(roundedBg(colors.primary, corner), corner, colors.onPrimary)
            setPadding(dp(Spacing.LG), dp(Spacing.LG), dp(Spacing.LG), dp(Spacing.LG))
            elevate(4f)
            setOnClickListener { TtsForegroundService.command(app, TtsForegroundService.ACTION_PLAY_PAUSE) }
        }

    val transport =
        LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(Spacing.MD), 0, 0)
        }
    transport.addView(transportIcon("Previous chapter", R.drawable.wna_skip_prev, TtsForegroundService.ACTION_PREVIOUS_CHAPTER))
    transport.addView(transportIcon("Previous sentence", R.drawable.wna_arrow_back, TtsForegroundService.ACTION_PREVIOUS))
    transport.addView(playPause)
    transport.addView(transportIcon("Next sentence", R.drawable.wna_arrow_forward, TtsForegroundService.ACTION_NEXT))
    transport.addView(transportIcon("Next chapter", R.drawable.wna_skip_next, TtsForegroundService.ACTION_NEXT_CHAPTER))
    content.addView(transport, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

    val chipsRow =
        LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(Spacing.MD), 0, 0)
        }

    fun makeChip(): LinearLayout =
        LinearLayout(app).apply {
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            val radius = dp(Spacing.LG).toFloat()
            background = ripple(roundedBg(android.graphics.Color.TRANSPARENT, radius), radius, colors.onSurface)
            setPadding(dp(Spacing.MD), dp(Spacing.XS + 2), dp(Spacing.MD), dp(Spacing.XS + 2))
        }

    val rateChip = makeChip()
    val sleepTimerChip = makeChip()

    chipsRow.addView(rateChip)
    chipsRow.addView(View(app), LinearLayout.LayoutParams(dp(Spacing.MD), 1))
    chipsRow.addView(sleepTimerChip)

    content.addView(
        chipsRow,
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
    )

    return PlayerLiveViews(app, playPause, progressLabel, seekBar, rateChip, sleepTimerChip).apply { apply(state) }
}
