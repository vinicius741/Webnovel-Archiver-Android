package com.vinicius741.webnovelarchiver.feature.narration

import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.data.repository.getTtsStoryPositions
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.feature.player.PlayerProgressLine
import com.vinicius741.webnovelarchiver.feature.player.playbackFraction
import com.vinicius741.webnovelarchiver.feature.player.playbackTimeLeft
import com.vinicius741.webnovelarchiver.feature.player.rateLabel
import com.vinicius741.webnovelarchiver.feature.player.showPlayer
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.tts.TtsContinueListening
import com.vinicius741.webnovelarchiver.tts.TtsForegroundService
import com.vinicius741.webnovelarchiver.tts.TtsPlaybackSnapshot
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.activeCoverSource
import com.vinicius741.webnovelarchiver.ui.coverImage
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeCard
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.ripple
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.selectableRipple
import com.vinicius741.webnovelarchiver.ui.tintedIcon
import kotlinx.coroutines.launch

/** The last Continue listening card shown, kept across the hub's rebuilds (main thread only). */
private var lastContinue: ContinueCard? = null

/** A Continue listening card's content; [key] holds everything it shows, so equal keys need no new card. */
private class ContinueCard(
    val target: TtsContinueListening.Target,
    val sentence: String?,
    val key: List<Any?>,
)

/**
 * The Listen hub's top card: what is playing (cover, chapter, story, progress, play/pause; opens
 * Now Playing), else the saved place to continue listening, else a short "nothing playing" note.
 * [bind] patches it in place on every playback update and rebuilds only when the story changes.
 */
internal class ListenNowCard(
    private val host: ScreenHost,
) {
    private val context = host.app
    private val colors = ThemeManager.colors
    private var mode: Mode? = null
    private var builtStoryId: String? = null
    private var playing: PlayingViews? = null

    val view: FrameLayout = FrameLayout(context)

    fun bind(snapshot: TtsPlaybackSnapshot?) {
        if (snapshot == null) {
            if (mode != Mode.IDLE) {
                mode = Mode.IDLE
                playing = null
                builtStoryId = null
                showIdle()
            }
            return
        }
        if (mode != Mode.PLAYING || builtStoryId != snapshot.storyId) {
            mode = Mode.PLAYING
            builtStoryId = snapshot.storyId
            playing = buildPlaying(snapshot)
        }
        playing?.apply(snapshot)
    }

    private fun buildPlaying(snapshot: TtsPlaybackSnapshot): PlayingViews {
        val eyebrow = eyebrow(colors.primary)
        val title = line(title = true)
        val story = line()
        val line = PlayerProgressLine(context, rounded = true)
        val meta = line()
        val play =
            playButton(colors.primary, colors.onPrimary) { TtsForegroundService.command(context, TtsForegroundService.ACTION_PLAY_PAUSE) }
        val texts =
            column(eyebrow, title, story).apply {
                addView(
                    line,
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dp(4)).apply {
                        topMargin =
                            context.dp(Spacing.SM + 2)
                    },
                )
                addView(
                    meta,
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        topMargin =
                            context.dp(6)
                    },
                )
            }
        setCard(
            cardGradient(context, PLAYING_START, PLAYING_END, colors.primary),
            host.repository.story(snapshot.storyId),
            texts,
            play,
        ) { host.showPlayer() }
        return PlayingViews(eyebrow, title, story, line, meta, play)
    }

    private inner class PlayingViews(
        val eyebrow: TextView,
        val title: TextView,
        val story: TextView,
        val line: PlayerProgressLine,
        val meta: TextView,
        val play: ImageView,
    ) {
        fun apply(snapshot: TtsPlaybackSnapshot) {
            val paused = snapshot.isPaused
            eyebrow.text = if (paused) "Paused" else "Playing now"
            title.text = snapshot.title
            story.text = snapshot.storyTitle
            line.fraction = playbackFraction(snapshot)
            val voice =
                host.narration.store
                    .pack(snapshot.storyId, snapshot.chapterId)
                    ?.recipe
                    ?.voice
                    ?.label
            meta.text = listOfNotNull(playbackTimeLeft(snapshot), rateLabel(snapshot.rate), voice).joinToString(" · ")
            play.setImageDrawable(context.tintedIcon(if (paused) R.drawable.wna_play else R.drawable.wna_pause, colors.onPrimary))
            play.contentDescription = if (paused) "Play" else "Pause"
        }
    }

    private fun showIdle() {
        // The hub rebuilds this card on every queue change; showing the last place found at once
        // (then checking it again) keeps the taller Continue card from replacing a short note each time.
        val shown = lastContinue
        if (shown != null) showContinue(shown) else showNothing()
        host.scope.launch {
            val positions = runCatching { host.repository.getTtsStoryPositions() }.getOrDefault(emptyList())
            val target = TtsContinueListening.target(positions) { host.repository.story(it) }
            if (mode != Mode.IDLE) return@launch
            if (target == null) {
                lastContinue = null
                if (shown != null) showNothing()
                return@launch
            }
            val position = positions.firstOrNull { it.storyId == target.story.id }
            val sentence =
                position
                    ?.takeIf {
                        !it.caughtUp && it.chapterId == target.chapter.id
                    }?.let { "sentence ${it.currentChunkIndex + 1}" }
            val key =
                listOf(
                    target.story.id,
                    target.story.title,
                    host.activeCoverSource(target.story),
                    target.chapter.id,
                    target.chapter.title,
                    sentence,
                )
            val found = ContinueCard(target, sentence, key)
            lastContinue = found
            if (shown?.key != key) showContinue(found)
        }
    }

    private fun showNothing() {
        view.removeAllViews()
        view.addView(
            makeCard(context).apply {
                addView(
                    makeText(context, "Nothing playing. Open a chapter and tap the headphones.", Type.BODY_MEDIUM, colors.onSurfaceVariant),
                )
            },
        )
    }

    private fun showContinue(card: ContinueCard) {
        val target = card.target
        val sentence = card.sentence
        val resume = {
            host.playIfGenerated(target.story.id, target.chapter.id) {
                TtsForegroundService.start(context, target.story.id, target.chapter.id)
            }
        }
        val texts =
            column(
                eyebrow(colors.secondary).apply { text = "Continue listening" },
                line(title = true).apply { text = target.chapter.title },
                line().apply { text = listOfNotNull(target.story.title, sentence).joinToString(" · ") },
            )
        val play = playButton(colors.secondary, colors.onSecondary, resume).apply { contentDescription = "Resume" }
        setCard(cardGradient(context, RESUME_START, RESUME_END, colors.secondary), target.story, texts, play, resume)
    }

    private fun setCard(
        background: GradientDrawable,
        story: Story?,
        texts: View,
        play: View,
        onOpen: () -> Unit,
    ) {
        view.removeAllViews()
        val hit =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                this.background = selectableRipple(colors.onSurface)
                setOnClickListener { onOpen() }
                story?.let { s ->
                    val cover = host.coverImage(s, COVER_WIDTH_DP, COVER_HEIGHT_DP, tapToOpen = false)
                    cover.isClickable = false
                    addView(
                        cover,
                        LinearLayout.LayoutParams(context.dp(COVER_WIDTH_DP), context.dp(COVER_HEIGHT_DP)).apply {
                            marginEnd =
                                context.dp(Spacing.MD)
                        },
                    )
                }
                addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
        val row =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                this.background = background
                val pad = context.dp(Spacing.MD + 2)
                setPadding(pad, pad, pad, pad)
                addView(hit, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(
                    play,
                    LinearLayout.LayoutParams(context.dp(PLAY_SIZE_DP), context.dp(PLAY_SIZE_DP)).apply {
                        marginStart =
                            context.dp(Spacing.MD)
                    },
                )
            }
        view.addView(row)
    }

    private fun column(vararg children: View) =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            children.forEach { addView(it) }
        }

    private fun eyebrow(color: Int) =
        makeText(context, "", Type.LABEL_MEDIUM, color).apply {
            isAllCaps = true
            letterSpacing = EYEBROW_SPACING
        }

    /** One ellipsized line: the bold title ([title]) or a muted supporting line. */
    private fun line(title: Boolean = false) =
        makeText(
            context,
            "",
            if (title) Type.TITLE_MEDIUM else Type.BODY_SMALL,
            if (title) colors.onSurface else colors.onSurfaceVariant,
        ).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            if (title) setPadding(0, context.dp(2), 0, 0)
        }

    private fun playButton(
        fill: Int,
        iconTint: Int,
        onClick: () -> Unit,
    ): ImageView {
        val radius = context.dp(PLAY_RADIUS_DP).toFloat()
        return ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            background = ripple(roundedBg(fill, radius), radius, iconTint)
            setImageDrawable(context.tintedIcon(R.drawable.wna_play, iconTint))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    private enum class Mode { PLAYING, IDLE }

    private companion object {
        const val COVER_WIDTH_DP = 64
        const val COVER_HEIGHT_DP = 96
        const val PLAY_SIZE_DP = 52
        const val PLAY_RADIUS_DP = 18
        const val EYEBROW_SPACING = 0.08f
        const val PLAYING_START = 0xFF33291A.toInt()
        const val PLAYING_END = 0xFF1D1A14.toInt()
        const val RESUME_START = 0xFF3A2618.toInt()
        const val RESUME_END = 0xFF1F1A15.toInt()
    }
}

private const val CARD_RADIUS_DP = 22
private const val BORDER_ALPHA_MASK = 0x33FFFFFF

/** The tinted card background: a diagonal gradient with a faint accent border. */
private fun cardGradient(
    context: android.content.Context,
    start: Int,
    end: Int,
    accent: Int,
) = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(start, end)).apply {
    cornerRadius = context.dp(CARD_RADIUS_DP).toFloat()
    setStroke(context.dp(1).coerceAtLeast(1), accent and BORDER_ALPHA_MASK)
}
