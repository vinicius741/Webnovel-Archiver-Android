package com.vinicius741.webnovelarchiver.feature.player

import android.content.Context
import android.content.res.ColorStateList
import android.os.SystemClock
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.feature.reader.showReader
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.tts.TtsForegroundService
import com.vinicius741.webnovelarchiver.tts.TtsPlaybackSnapshot
import com.vinicius741.webnovelarchiver.tts.TtsTimeFormat
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.coverImage
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.elevate
import com.vinicius741.webnovelarchiver.ui.iconButton
import com.vinicius741.webnovelarchiver.ui.makeButton
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.ripple
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.tintedIcon
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

private const val TIME_TICK_MS = 1_000L

/** Speed presets cycled by the player's speed chip. */
internal val RATE_PRESETS = floatArrayOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 1.8f, 2.0f)

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

private class PlayerTimeRow(
    val elapsed: TextView,
    val remaining: TextView,
)

/** Live-patched views inside the player screen (transport icon, time, speed label, sleep timer). */
private class PlayerLiveViews(
    private val context: Context,
    private val playPauseButton: ImageView,
    private val times: PlayerTimeRow,
    private val seekBar: SeekBar,
    private val rateChip: LinearLayout,
    private val sleepTimerChip: LinearLayout,
    private val historyChip: LinearLayout,
) {
    private var currentRate: Float = 1f
    private var isUserSeeking: Boolean = false
    private var state: TtsPlaybackSnapshot? = null

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
        historyChip.setOnClickListener { showHistory() }
        seekBar.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    sb: SeekBar?,
                    progress: Int,
                    fromUser: Boolean,
                ) {
                    if (fromUser) renderTime(progress * SEEK_STEP_MS)
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {
                    isUserSeeking = true
                }

                override fun onStopTrackingTouch(sb: SeekBar?) {
                    isUserSeeking = false
                    TtsForegroundService.seekTo(context, (sb?.progress ?: 0) * SEEK_STEP_MS)
                }
            },
        )
    }

    fun apply(next: TtsPlaybackSnapshot?) {
        state = next
        currentRate = next?.rate ?: 1f
        val isPaused = next?.isPaused != false
        playPauseButton.setImageDrawable(
            context.tintedIcon(if (isPaused) R.drawable.wna_play else R.drawable.wna_pause, ThemeManager.colors.onPrimary),
        )
        playPauseButton.contentDescription = if (isPaused) "Play" else "Pause"
        tick()
        rateChip.removeAllViews()
        rateChip.addView(makeText(context, rateLabel(currentRate), Type.LABEL_LARGE, ThemeManager.colors.primary))
        sleepTimerChip.removeAllViews()
        val timerText = sleepTimerLabel(next?.sleepTimerTargetEpochMs, next?.sleepTimerEndOfChapter == true)
        val timerColor =
            if (next?.sleepTimerTargetEpochMs != null || next?.sleepTimerEndOfChapter == true) {
                ThemeManager.colors.primary
            } else {
                ThemeManager.colors.onSurfaceVariant
            }
        sleepTimerChip.addView(makeText(context, timerText, Type.LABEL_LARGE, timerColor))
        historyChip.visibility = if (next?.history.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    /** Advances the time labels between engine updates, which only come per sentence. */
    fun tick() {
        if (isUserSeeking) return
        val current = state
        if (current == null || current.durationMs <= 0L) {
            times.elapsed.text = "Buffering"
            times.remaining.text = ""
            seekBar.max = 0
            return
        }
        val position = current.positionAt(SystemClock.elapsedRealtime())
        seekBar.max = (current.durationMs / SEEK_STEP_MS).toInt()
        seekBar.progress = (position / SEEK_STEP_MS).toInt()
        renderTime(position)
    }

    private fun renderTime(positionMs: Long) {
        val current = state ?: return
        val estimate = if (current.timingExact) "" else "~"
        times.elapsed.text = TtsTimeFormat.clock(positionMs)
        times.remaining.text =
            "$estimate-${TtsTimeFormat.clock(TtsTimeFormat.remainingAtRate(positionMs, current.durationMs, current.rate))}"
    }

    private fun showHistory() {
        val entries = state?.history.orEmpty()
        if (entries.isEmpty()) return
        val now = System.currentTimeMillis()
        val labels =
            entries
                .map { entry ->
                    val age = DateUtils.getRelativeTimeSpanString(entry.recordedAt, now, DateUtils.MINUTE_IN_MILLIS)
                    val excerpt = entry.anchorText?.let { " · \"$it…\"" }.orEmpty()
                    "${entry.chapterTitle} · $age$excerpt"
                }.toTypedArray()
        android.app.AlertDialog
            .Builder(context)
            .setTitle("Go back to")
            .setItems(labels) { _, which -> TtsForegroundService.jumpToHistory(context, entries[which]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private companion object {
        const val SEEK_STEP_MS = 1_000L
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
    ) {
        liveViews = buildPlayerBody(this, initial)
    }

    screenObserver =
        scope.launch {
            launch {
                while (true) {
                    delay(TIME_TICK_MS)
                    liveViews?.tick()
                }
            }
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
    val timeRow =
        LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(Spacing.MD), 0, dp(Spacing.MD), 0)
        }
    val elapsedLabel = makeText(app, "", Type.BODY_SMALL, colors.onSurfaceVariant)
    val remainingLabel = makeText(app, "", Type.BODY_SMALL, colors.onSurfaceVariant).apply { gravity = Gravity.END }
    timeRow.addView(elapsedLabel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    timeRow.addView(remainingLabel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    content.addView(timeRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

    val playPause = buildPlayPauseButton()
    content.addView(
        buildPlayerTransport(playPause),
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
    )

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
    val historyChip = makeChip().apply { addView(makeText(app, "History", Type.LABEL_LARGE, colors.onSurfaceVariant)) }
    val stopChip =
        makeChip().apply {
            setOnClickListener { TtsForegroundService.command(app, TtsForegroundService.ACTION_STOP) }
            addView(makeText(app, "Stop", Type.LABEL_LARGE, colors.onSurfaceVariant))
        }

    chipsRow.addView(rateChip)
    chipsRow.addView(View(app), LinearLayout.LayoutParams(dp(Spacing.MD), 1))
    chipsRow.addView(sleepTimerChip)
    chipsRow.addView(View(app), LinearLayout.LayoutParams(dp(Spacing.MD), 1))
    chipsRow.addView(historyChip)
    chipsRow.addView(View(app), LinearLayout.LayoutParams(dp(Spacing.MD), 1))
    chipsRow.addView(stopChip)

    content.addView(
        chipsRow,
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
    )

    if (chapterIndex >= 0 && story != null) addReadAlongButton(content, state)

    return PlayerLiveViews(
        app,
        playPause,
        PlayerTimeRow(elapsedLabel, remainingLabel),
        seekBar,
        rateChip,
        sleepTimerChip,
        historyChip,
    ).apply { apply(state) }
}

/** Opens the playing chapter in the reader, which highlights and follows the spoken sentence. */
private fun ScreenHost.addReadAlongButton(
    content: LinearLayout,
    state: TtsPlaybackSnapshot,
) {
    content.addView(
        makeButton(app, "Read along", Btn.TONAL, R.drawable.wna_menu_book) {
            val live = ttsEngine.playbackState.value.snapshot ?: state
            // Readers of this story below Now Playing are dropped rather than returned to, so Back
            // from the reader lands here (an older one would also auto-jump to the playing chapter
            // as soon as it reopened, swallowing a Back press).
            navigator.bringToTop(AppRoute.Reader(live.storyId, live.chapterId)) {
                it is AppRoute.Reader && it.storyId == live.storyId
            }
            showReader(live.storyId, live.chapterId)
        },
        LinearLayout
            .LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(Spacing.LG)
            },
    )
}

private fun ScreenHost.buildPlayPauseButton(): ImageView {
    val colors = ThemeManager.colors
    return ImageView(app).apply {
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
}

private fun ScreenHost.buildPlayerTransport(playPause: ImageView): LinearLayout {
    fun transportIcon(
        desc: String,
        icon: Int,
        action: String,
    ) = app.iconButton(icon, desc, ThemeManager.colors.primary) { TtsForegroundService.command(app, action) }

    return LinearLayout(app).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL or Gravity.CENTER_HORIZONTAL
        setPadding(0, dp(Spacing.MD), 0, 0)
        addView(transportIcon("Previous chapter", R.drawable.wna_skip_prev, TtsForegroundService.ACTION_PREVIOUS_CHAPTER))
        addView(transportIcon("Back 15 seconds", R.drawable.wna_replay, TtsForegroundService.ACTION_SKIP_BACK))
        addView(playPause)
        addView(transportIcon("Forward 30 seconds", R.drawable.wna_forward_skip, TtsForegroundService.ACTION_SKIP_FORWARD))
        addView(transportIcon("Next chapter", R.drawable.wna_skip_next, TtsForegroundService.ACTION_NEXT_CHAPTER))
    }
}
