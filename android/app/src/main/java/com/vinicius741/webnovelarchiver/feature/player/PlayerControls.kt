package com.vinicius741.webnovelarchiver.feature.player

import android.content.Context
import android.content.res.ColorStateList
import android.os.SystemClock
import android.text.TextUtils
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.tts.TtsForegroundService
import com.vinicius741.webnovelarchiver.tts.TtsPlaybackSnapshot
import com.vinicius741.webnovelarchiver.tts.TtsTimeFormat
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeSheetRow
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.ripple
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.showBottomSheet
import com.vinicius741.webnovelarchiver.ui.tintedIcon

/** A Now Playing chip: icon + label on a translucent pill; gold container while [setOn]. */
internal class PlayerChip(
    context: Context,
    iconRes: Int,
    label: String,
    onClick: () -> Unit,
) {
    private val colors = ThemeManager.colors
    private val icon = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_INSIDE }
    private val iconRes = iconRes
    private val text = makeText(context, label, Type.LABEL_LARGE, colors.onSurface)
    val view: LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            minimumHeight = context.dp(CHIP_HEIGHT_DP)
            setPadding(context.dp(Spacing.MD), 0, context.dp(Spacing.MD + 2), 0)
            addView(icon, LinearLayout.LayoutParams(context.dp(ICON_DP), context.dp(ICON_DP)).apply { marginEnd = context.dp(6) })
            addView(text)
            setOnClickListener { onClick() }
        }

    init {
        setOn(false)
    }

    fun setLabel(label: String) {
        text.text = label
        view.contentDescription = label
    }

    fun setOn(on: Boolean) {
        val context = view.context
        val radius = context.dp(CHIP_HEIGHT_DP).toFloat() / 2
        val fill = if (on) colors.primaryContainer else CHIP_FILL
        view.background = ripple(roundedBg(fill, radius), radius, colors.onSurface)
        val fg = if (on) colors.onPrimaryContainer else colors.onSurface
        text.setTextColor(fg)
        icon.setImageDrawable(context.tintedIcon(iconRes, if (on) colors.onPrimaryContainer else colors.onSurfaceVariant))
    }

    private companion object {
        const val CHIP_HEIGHT_DP = 36
        const val ICON_DP = 18
        const val CHIP_FILL = 0x0DFFFFFF
    }
}

/** The elapsed and remaining labels under the seek bar. */
internal class PlayerTimes(
    val elapsed: TextView,
    val remaining: TextView,
)

/** The "Now reading" card and its sentence text. */
internal class NowReadingCaption(
    val text: TextView,
    val card: View,
)

/** Views Now Playing patches in place between rebuilds (rebuilds happen only on chapter changes). */
internal class NowPlayingViews(
    private val context: Context,
    private val playPause: ImageView,
    private val times: PlayerTimes,
    private val seekBar: SeekBar,
    private val caption: NowReadingCaption,
    private val rateChip: PlayerChip,
    private val sleepChip: PlayerChip,
) {
    private var state: TtsPlaybackSnapshot? = null
    private var isUserSeeking = false

    /** The chapter's spoken sentences, aligned with the engine's chunk indices; empty until loaded. */
    var sentences: List<String> = emptyList()
        set(value) {
            field = value
            renderCaption()
        }

    init {
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
        val isPaused = next?.isPaused != false
        playPause.setImageDrawable(
            context.tintedIcon(if (isPaused) R.drawable.wna_play else R.drawable.wna_pause, ThemeManager.colors.onPrimary),
        )
        playPause.contentDescription = if (isPaused) "Play" else "Pause"
        rateChip.setLabel(rateLabel(next?.rate ?: 1f))
        val timerOn = next?.sleepTimerTargetEpochMs != null || next?.sleepTimerEndOfChapter == true
        sleepChip.setLabel(sleepTimerLabel(next?.sleepTimerTargetEpochMs, next?.sleepTimerEndOfChapter == true))
        sleepChip.setOn(timerOn)
        renderCaption()
        tick()
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

    private fun renderCaption() {
        val sentence = state?.chunkIndex?.let { sentences.getOrNull(it) }
        caption.text.text = sentence.orEmpty()
        caption.card.visibility = if (sentences.isEmpty()) View.GONE else View.VISIBLE
    }

    fun showSleepTimerSheet() {
        val current = state
        val active = current?.sleepTimerTargetEpochMs
        val remainingMin = active?.let { ((it - System.currentTimeMillis() + MINUTE_MS - 1) / MINUTE_MS).toInt() }
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        lateinit var dismiss: () -> Unit
        SLEEP_OPTIONS.forEach { (label, minutes) ->
            val checked =
                when (minutes) {
                    0 -> active == null && current?.sleepTimerEndOfChapter != true
                    END_OF_CHAPTER -> current?.sleepTimerEndOfChapter == true
                    else -> false
                }
            val icon = if (minutes == 0) R.drawable.wna_close else R.drawable.wna_bedtime
            body.addView(
                makeSheetRow(context, label, iconRes = icon, checked = checked) {
                    dismiss()
                    TtsForegroundService.setSleepTimer(context, minutes)
                },
            )
        }
        val subtitle = remainingMin?.let { "Stops in $it min" }
        val sheet = showBottomSheet(context, title = "Sleep timer", subtitle = subtitle, body = body)
        dismiss = sheet::dismiss
    }

    fun showHistorySheet() {
        val entries = state?.history.orEmpty()
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        lateinit var dismiss: () -> Unit
        val now = System.currentTimeMillis()
        entries.forEach { entry ->
            val age = DateUtils.getRelativeTimeSpanString(entry.recordedAt, now, DateUtils.MINUTE_IN_MILLIS)
            val excerpt = entry.anchorText?.let { " · “$it…”" }.orEmpty()
            body.addView(
                makeSheetRow(context, entry.chapterTitle, supporting = "$age$excerpt", iconRes = R.drawable.wna_history) {
                    dismiss()
                    TtsForegroundService.jumpToHistory(context, entry)
                },
            )
        }
        val subtitle =
            if (entries.isEmpty()) "Nothing yet. Places appear here when you skip or change chapters." else "Recent listening places"
        val sheet = showBottomSheet(context, title = "Go back to", subtitle = subtitle, body = body)
        dismiss = sheet::dismiss
    }

    private companion object {
        const val SEEK_STEP_MS = 1_000L
        const val MINUTE_MS = 60_000L
        const val END_OF_CHAPTER = -1
        val SLEEP_OPTIONS =
            listOf(
                "Off" to 0,
                "15 minutes" to 15,
                "30 minutes" to 30,
                "45 minutes" to 45,
                "60 minutes" to 60,
                "End of chapter" to END_OF_CHAPTER,
            )
    }
}

/** The gold seek bar: the only slider the v3 player keeps. */
internal fun makePlayerSeekBar(context: Context): SeekBar =
    SeekBar(context).apply {
        val colors = ThemeManager.colors
        progressTintList = ColorStateList.valueOf(colors.primary)
        progressBackgroundTintList = ColorStateList.valueOf(colors.elevation5)
        thumbTintList = ColorStateList.valueOf(colors.primary)
        contentDescription = "Seek"
    }

/** A muted time label under the seek bar. */
internal fun makeTimeLabel(
    context: Context,
    gravity: Int,
): TextView =
    makeText(context, "", Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant).apply {
        this.gravity = gravity
        fontFeatureSettings = "tnum"
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
