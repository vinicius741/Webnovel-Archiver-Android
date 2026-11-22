package com.vinicius741.webnovelarchiver.feature.player

import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import coil3.load
import coil3.request.crossfade
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.feature.details.showDetails
import com.vinicius741.webnovelarchiver.feature.details.showStoryChapters
import com.vinicius741.webnovelarchiver.feature.narration.narration
import com.vinicius741.webnovelarchiver.feature.narration.showNarration
import com.vinicius741.webnovelarchiver.feature.reader.showReader
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.tts.TtsPlaybackSnapshot
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.ScreenChrome
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.WrapLayout
import com.vinicius741.webnovelarchiver.ui.activeCoverSource
import com.vinicius741.webnovelarchiver.ui.bindWhileAttached
import com.vinicius741.webnovelarchiver.ui.coverImage
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.iconButton
import com.vinicius741.webnovelarchiver.ui.makeButton
import com.vinicius741.webnovelarchiver.ui.makeEmptyState
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.selectableRipple
import com.vinicius741.webnovelarchiver.ui.systemBarTop
import com.vinicius741.webnovelarchiver.ui.tickerFlow

private const val TIME_TICK_MS = 1_000L

/** Now Playing's model, kept while the player is in the history. */
private fun ScreenHost.playerModel(): PlayerScreenModel =
    screenModels.get(AppRoute.Player.stableKey) {
        PlayerScreenModel(RepositoryPlayerData(repository, narration, ttsEngine), ServicePlaybackControls(this))
    }

/**
 * Podcast-style Now Playing (v3): blurred cover backdrop, collapse button, big cover, chapter and
 * story, the "Now reading" card with the sentence being spoken, seek bar, transport, chips (speed,
 * sleep timer, history, stop) and Read along. Built from [PlayerScreenModel]'s state; see
 * [PlayerRenderer] for how it follows it.
 */
internal fun ScreenHost.showPlayer() {
    rerender = { showPlayer() }
    var renderer: PlayerRenderer? = null
    screen(route = AppRoute.Player, title = "Now Playing", chrome = ScreenChrome.IMMERSIVE) {
        val model = playerModel()
        renderer = PlayerRenderer(this@showPlayer, model, model.refresh()).also { it.build(this) }
    }
    renderer?.start()
}

/**
 * One render of Now Playing, bound to [model] while its views are on screen. It rebuilds the
 * screen when playback moves to another chapter (or starts from nothing), patches the controls in
 * place on every playback update and once a second for the time labels, and closes the screen when
 * playback is stopped.
 */
private class PlayerRenderer(
    private val host: ScreenHost,
    private val model: PlayerScreenModel,
    /** The state this render was built from. */
    private val built: PlayerUiState,
) {
    private val app = host.app
    private var root: View? = null
    private var views: NowPlayingViews? = null
    private var nothingBox: LinearLayout? = null
    private var continueButton: View? = null
    private var shownContinue: ContinuePlace? = null

    fun start() {
        val root = root ?: return
        root.bindWhileAttached(host.scope, model.state) { apply(it) }
        root.bindWhileAttached(host.scope, tickerFlow(TIME_TICK_MS)) { views?.tick() }
    }

    private fun apply(next: PlayerUiState) {
        if (root?.isAttachedToWindow != true) return
        val shown = built.chapterKey
        if (next.snapshot == null) {
            if (next.stopped && shown != null && host.navigator.current == AppRoute.Player) {
                host.navigateBack()
            } else if (shown == null) {
                showContinue(next.continuePlace)
            }
            return
        }
        if (next.chapterKey != shown) {
            host.showPlayer()
            return
        }
        views?.apply(next.snapshot)
        views?.sentences = next.sentences
    }

    fun build(content: LinearLayout) {
        val state = built.snapshot
        val story = built.story
        val backdrop = PlayerBackdrop(app)
        story?.let { s -> host.activeCoverSource(s)?.let { backdrop.load(it) { crossfade(false) } } }
        val column =
            LinearLayout(app).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(app.dp(Spacing.LG), host.systemBarTop(), app.dp(Spacing.LG), app.dp(Spacing.XL))
            }
        column.addView(playerHeader())
        if (state == null) {
            column.addView(nothingPlaying(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        } else {
            val wide = app.resources.configuration.screenWidthDp >= WIDE_MIN_DP
            val body =
                LinearLayout(app).apply {
                    orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                }
            story?.let {
                // On a phone the cover takes whatever height the controls leave, so the screen fits without scrolling.
                val artParams =
                    if (wide) {
                        LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    } else {
                        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                    }
                body.addView(playerArt(it, wide), artParams)
            }
            val controls = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL }
            views = buildPlayerControls(controls, state, story, wide)
            body.addView(
                controls,
                if (wide) {
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                } else {
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                },
            )
            // Cap the wide body to the space inside the column's side padding; a fixed width wider than
            // the screen (e.g. a foldable's inner display) would be centered and clipped on both edges.
            val availableDp = app.resources.configuration.screenWidthDp - 2 * Spacing.LG
            val bodyWidth = if (wide) app.dp(minOf(WIDE_BODY_MAX_DP, availableDp)) else LinearLayout.LayoutParams.MATCH_PARENT
            column.addView(
                body,
                LinearLayout.LayoutParams(bodyWidth, 0, 1f).apply { gravity = Gravity.CENTER_HORIZONTAL },
            )
        }
        val scroller =
            ScrollView(app).apply {
                isFillViewport = true
                addView(column)
            }
        val frame =
            FrameLayout(app).apply {
                addView(backdrop, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                addView(scroller, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            }
        content.addView(frame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root = frame
        views?.apply(state)
        views?.sentences = built.sentences
    }

    private fun playerHeader(): View {
        val colors = ThemeManager.colors
        return LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = app.dp(HEADER_HEIGHT_DP)
            addView(app.iconButton(R.drawable.wna_chevron_down, "Collapse", colors.onSurface) { host.navigateBack() })
            val titles =
                LinearLayout(app).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    addView(makeText(app, "Now Playing", Type.TITLE_SMALL, colors.onSurface))
                    built.voice?.let { addView(makeText(app, "Voice: $it", Type.BODY_SMALL, colors.onSurfaceVariant)) }
                }
            addView(titles, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(
                app.iconButton(R.drawable.wna_graphic_eq, "Chapters and audio", colors.onSurface) {
                    val snapshot = model.state.value.snapshot
                    if (snapshot != null) host.showStoryChapters(snapshot.storyId, snapshot.chapterId) else host.showNarration()
                },
            )
        }
    }

    private fun playerArt(
        story: Story,
        wide: Boolean,
    ): View {
        val config = app.resources.configuration
        val widthDp = if (wide) WIDE_COVER_DP else minOf(config.screenWidthDp * 56 / 100, COVER_MAX_DP)
        val cover =
            host.coverImage(story, widthDp, widthDp * 3 / 2, tapToOpen = false).apply {
                contentDescription = "Open ${story.title}"
                setOnClickListener { host.showDetails(story.id) }
                elevation = app.dp(Spacing.LG).toFloat()
            }
        if (wide) {
            return FrameLayout(app).apply {
                setPadding(0, 0, app.dp(WIDE_GAP_DP), 0)
                clipToPadding = false
                addView(cover, FrameLayout.LayoutParams(app.dp(widthDp), app.dp(widthDp * 3 / 2), Gravity.CENTER))
            }
        }
        return FitCoverFrame(app, maxWidthPx = app.dp(widthDp), minWidthPx = app.dp(minOf(widthDp, COVER_MIN_DP))).apply {
            setPadding(0, app.dp(Spacing.XS), 0, app.dp(Spacing.LG + 2))
            clipToPadding = false
            addView(cover, FrameLayout.LayoutParams(app.dp(widthDp), app.dp(widthDp * 3 / 2), Gravity.CENTER))
        }
    }

    @Suppress("LongMethod") // One linear layout pass over the player's controls.
    private fun buildPlayerControls(
        controls: LinearLayout,
        state: TtsPlaybackSnapshot,
        story: Story?,
        wide: Boolean,
    ): NowPlayingViews {
        val colors = ThemeManager.colors
        val align = if (wide) Gravity.START else Gravity.CENTER_HORIZONTAL
        val chapterIndex = story?.chapters?.indexOfFirst { it.id == state.chapterId } ?: -1
        controls.addView(
            makeText(app, state.title, Type.TITLE_LARGE, colors.onSurface).apply {
                gravity = align
                textSize = CHAPTER_TITLE_SP
            },
        )
        val storyTitle = state.storyTitle.ifBlank { story?.title.orEmpty() }
        controls.addView(
            makeText(app, storyTitle, Type.TITLE_SMALL, colors.primary).apply {
                gravity = align
                setPadding(0, app.dp(Spacing.XS), 0, app.dp(2))
                if (story != null) {
                    background = selectableRipple(colors.primary)
                    setOnClickListener { host.showDetails(story.id) }
                }
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity =
                    align
            },
        )
        if (chapterIndex >= 0 && story != null) {
            controls.addView(
                makeText(app, "Chapter ${chapterIndex + 1} / ${story.chapters.size}", Type.BODY_SMALL, colors.onSurfaceVariant).apply {
                    gravity =
                        align
                },
            )
        }
        val canReadAlong = chapterIndex >= 0 && story != null
        // Always three lines tall, so a sentence wrapping to more or fewer lines never shifts the controls below.
        val caption =
            makeText(app, "", Type.BODY_LARGE, colors.onSurface).apply {
                setLines(CAPTION_LINES)
                ellipsize = TextUtils.TruncateAt.END
            }
        val captionCard = app.nowReadingCard(caption) { if (canReadAlong) openReadAlong(state) }
        controls.addView(captionCard)

        val seekBar = makePlayerSeekBar(app)
        controls.addView(seekBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val elapsed = makeTimeLabel(app, Gravity.START)
        val remaining = makeTimeLabel(app, Gravity.END)
        controls.addView(
            LinearLayout(app).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(app.dp(Spacing.LG), 0, app.dp(Spacing.LG), 0)
                addView(elapsed, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(remaining, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            },
        )

        val playPause = app.playButton(model::playPause)
        controls.addView(app.transportRow(playPause, model))

        lateinit var views: NowPlayingViews
        val rateChip =
            PlayerChip(app, R.drawable.wna_speed, rateLabel(state.rate)) {
                model.cycleRate()
            }
        val sleepChip = PlayerChip(app, R.drawable.wna_bedtime, "Timer: Off") { views.showSleepTimerSheet() }
        val historyChip = PlayerChip(app, R.drawable.wna_history, "History") { views.showHistorySheet() }
        val stopChip = PlayerChip(app, R.drawable.wna_stop, "Stop") { model.stop() }
        val chips =
            WrapLayout(app).apply {
                horizontalSpacingDp = Spacing.SM
                verticalSpacingDp = Spacing.SM
                listOf(rateChip, sleepChip, historyChip, stopChip).forEach { addView(it.view) }
            }
        controls.addView(
            chips,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity =
                    align
            },
        )
        if (canReadAlong) {
            controls.addView(
                makeButton(
                    app,
                    "Read along",
                    Btn.TONAL,
                    R.drawable.wna_menu_book,
                ) { openReadAlong(state) }.apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = app.dp(Spacing.LG)
                },
            )
        }
        views =
            NowPlayingViews(
                app,
                playPause,
                PlayerTimes(elapsed, remaining),
                seekBar,
                NowReadingCaption(caption, captionCard),
                rateChip,
                sleepChip,
                model,
            )
        return views
    }

    /** Opens the playing chapter in the reader, which highlights and follows the spoken sentence. */
    private fun openReadAlong(state: TtsPlaybackSnapshot) {
        val live = model.state.value.snapshot ?: state
        // Readers of this story below Now Playing are dropped rather than returned to, so Back from the
        // reader lands here (an older one would also auto-jump to the playing chapter as soon as it
        // reopened, swallowing a Back press).
        host.navigator.bringToTop(AppRoute.Reader(live.storyId, live.chapterId)) {
            it is AppRoute.Reader && it.storyId == live.storyId
        }
        host.showReader(live.storyId, live.chapterId)
    }

    /** Nothing loaded: say so, and offer the saved place when there is one. */
    private fun nothingPlaying(): View {
        val box =
            LinearLayout(app).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
            }
        box.addView(
            makeEmptyState(
                app,
                message = "Open a chapter and tap the headphones to listen.",
                iconRes = R.drawable.wna_headphones,
                title = "Nothing playing",
            ),
        )
        nothingBox = box
        showContinue(built.continuePlace)
        return box
    }

    /** Adds or updates the "Continue" button under "Nothing playing" as the saved place changes. */
    private fun showContinue(place: ContinuePlace?) {
        val box = nothingBox ?: return
        if (place == shownContinue && (continueButton != null) == (place != null)) return
        shownContinue = place
        continueButton?.let(box::removeView)
        continueButton = null
        val target = place?.target ?: return
        val button =
            makeButton(app, "Continue · ${target.chapter.title}", Btn.FILLED, R.drawable.wna_play) { model.continueListening() }
        box.addView(
            button,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            },
        )
        continueButton = button
    }
}

/**
 * Holds the 2:3 cover at the largest size that fits the height it is given, between [minWidthPx]
 * and [maxWidthPx]. Asked to wrap its content, it reports the smallest size, so the controls below
 * are measured first and the cover grows into the space they leave.
 */
private class FitCoverFrame(
    context: android.content.Context,
    private val maxWidthPx: Int,
    private val minWidthPx: Int,
) : FrameLayout(context) {
    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        val verticalPadding = paddingTop + paddingBottom
        val widthLimit =
            if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
                maxWidthPx
            } else {
                MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
            }
        val exactHeight = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY
        val heightLimit = if (exactHeight) MeasureSpec.getSize(heightMeasureSpec) - verticalPadding else 0
        val coverWidth = minOf(maxWidthPx, widthLimit, heightLimit * 2 / 3).coerceAtLeast(minWidthPx)
        val coverHeight = coverWidth * 3 / 2
        getChildAt(0)?.let { child ->
            child.measure(
                MeasureSpec.makeMeasureSpec(coverWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(coverHeight, MeasureSpec.EXACTLY),
            )
        }
        val height = if (exactHeight) MeasureSpec.getSize(heightMeasureSpec) else coverHeight + verticalPadding
        setMeasuredDimension(resolveSize(coverWidth + paddingLeft + paddingRight, widthMeasureSpec), height)
    }
}

private const val WIDE_MIN_DP = 720
private const val WIDE_BODY_MAX_DP = 860
private const val WIDE_COVER_DP = 300
private const val WIDE_GAP_DP = 48
private const val COVER_MAX_DP = 230
private const val COVER_MIN_DP = 120
private const val HEADER_HEIGHT_DP = 64
private const val CHAPTER_TITLE_SP = 21f
private const val CAPTION_LINES = 3
