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
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.feature.player.ContinuePlace
import com.vinicius741.webnovelarchiver.feature.player.PlayerProgressLine
import com.vinicius741.webnovelarchiver.feature.player.playbackFraction
import com.vinicius741.webnovelarchiver.feature.player.playbackTimeLeft
import com.vinicius741.webnovelarchiver.feature.player.rateLabel
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
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

/**
 * The Listen hub's top card: what is playing (cover, chapter, story, progress, play/pause; opens
 * Now Playing), else the saved place to continue listening, else a short "nothing playing" note.
 * [bind] patches it in place on every playback update and rebuilds it only when the story, or the
 * place to continue, changes.
 */
internal class ListenNowCard(
    private val host: ScreenHost,
    private val onPlayPause: () -> Unit,
    private val onResume: (ContinuePlace) -> Unit,
    private val onOpenPlayer: () -> Unit,
) {
    private val context = host.app
    private val colors = ThemeManager.colors
    private var playing: PlayingViews? = null
    private var builtStoryId: String? = null

    /** What the idle card shows (a place to continue, or nothing); equal keys need no new card. */
    private var idleKey: List<Any?>? = null

    val view: FrameLayout = FrameLayout(context)

    fun bind(now: ListenNow) {
        when (now) {
            is ListenNow.Playing -> {
                idleKey = null
                if (playing == null || builtStoryId != now.snapshot.storyId) {
                    builtStoryId = now.snapshot.storyId
                    playing = buildPlaying(now.story)
                }
                playing?.apply(now)
            }
            is ListenNow.Continue -> showIdle(continueKey(now.place)) { showContinue(now.place) }
            ListenNow.Nothing -> showIdle(NOTHING_KEY, ::showNothing)
        }
    }

    private fun showIdle(
        key: List<Any?>,
        show: () -> Unit,
    ) {
        playing = null
        builtStoryId = null
        if (idleKey == key) return
        idleKey = key
        show()
    }

    private fun continueKey(place: ContinuePlace): List<Any?> {
        val target = place.target
        return listOf(
            target.story.id,
            target.story.title,
            host.activeCoverSource(target.story),
            target.chapter.id,
            target.chapter.title,
            place.sentence,
        )
    }

    private fun buildPlaying(story: Story?): PlayingViews {
        val eyebrow = eyebrow(colors.primary)
        val title = line(title = true)
        val storyLine = line()
        val line = PlayerProgressLine(context, rounded = true)
        val meta = line()
        val play = playButton(colors.primary, colors.onPrimary, onPlayPause)
        val texts =
            column(eyebrow, title, storyLine).apply {
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
        setCard(cardGradient(context, PLAYING_START, PLAYING_END, colors.primary), story, texts, play, onOpenPlayer)
        return PlayingViews(eyebrow, title, storyLine, line, meta, play)
    }

    private inner class PlayingViews(
        val eyebrow: TextView,
        val title: TextView,
        val story: TextView,
        val line: PlayerProgressLine,
        val meta: TextView,
        val play: ImageView,
    ) {
        fun apply(now: ListenNow.Playing) {
            val snapshot = now.snapshot
            val paused = snapshot.isPaused
            eyebrow.text = if (paused) "Paused" else "Playing now"
            title.text = snapshot.title
            story.text = snapshot.storyTitle
            line.fraction = playbackFraction(snapshot)
            meta.text = listOfNotNull(playbackTimeLeft(snapshot), rateLabel(snapshot.rate), now.voice).joinToString(" · ")
            play.setImageDrawable(context.tintedIcon(if (paused) R.drawable.wna_play else R.drawable.wna_pause, colors.onPrimary))
            play.contentDescription = if (paused) "Play" else "Pause"
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

    private fun showContinue(place: ContinuePlace) {
        val target = place.target
        val resume = { onResume(place) }
        val texts =
            column(
                eyebrow(colors.secondary).apply { text = "Continue listening" },
                line(title = true).apply { text = target.chapter.title },
                line().apply { text = listOfNotNull(target.story.title, place.sentence).joinToString(" · ") },
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

    private companion object {
        val NOTHING_KEY = listOf<Any?>("nothing")
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
