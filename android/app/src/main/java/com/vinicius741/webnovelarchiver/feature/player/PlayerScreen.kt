package com.vinicius741.webnovelarchiver.feature.player

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import coil3.load
import coil3.request.crossfade
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.cleanup.TtsTextPreparation
import com.vinicius741.webnovelarchiver.data.repository.getTtsStoryPositions
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.feature.details.showDetails
import com.vinicius741.webnovelarchiver.feature.narration.narration
import com.vinicius741.webnovelarchiver.feature.narration.playIfGenerated
import com.vinicius741.webnovelarchiver.feature.narration.showNarration
import com.vinicius741.webnovelarchiver.feature.narration.showNarrationStory
import com.vinicius741.webnovelarchiver.feature.reader.showReader
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.tts.TtsContinueListening
import com.vinicius741.webnovelarchiver.tts.TtsForegroundService
import com.vinicius741.webnovelarchiver.tts.TtsPlaybackSnapshot
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.ScreenChrome
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.WrapLayout
import com.vinicius741.webnovelarchiver.ui.activeCoverSource
import com.vinicius741.webnovelarchiver.ui.coverImage
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.iconButton
import com.vinicius741.webnovelarchiver.ui.makeButton
import com.vinicius741.webnovelarchiver.ui.makeEmptyState
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.ripple
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.selectableRipple
import com.vinicius741.webnovelarchiver.ui.strokeBg
import com.vinicius741.webnovelarchiver.ui.systemBarTop
import com.vinicius741.webnovelarchiver.ui.tintedIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TIME_TICK_MS = 1_000L

/**
 * Podcast-style Now Playing (v3): blurred cover backdrop, collapse button, big cover, chapter and
 * story, the "Now reading" card with the sentence being spoken, seek bar, transport, chips (speed,
 * sleep timer, history, stop) and Read along. Rebuilds when playback moves to another chapter;
 * patches the controls in place otherwise. Closes itself when playback ends.
 */
internal fun ScreenHost.showPlayer() {
    rerender = { showPlayer() }
    val initial = ttsEngine.playbackState.value.snapshot
    var liveViews: NowPlayingViews? = null

    screen(route = AppRoute.Player, title = "Now Playing", chrome = ScreenChrome.IMMERSIVE) {
        liveViews = buildNowPlaying(this, initial)
    }

    screenObserver =
        scope.launch {
            launch {
                while (true) {
                    delay(TIME_TICK_MS)
                    liveViews?.tick()
                }
            }
            if (initial != null) {
                launch {
                    val sentences = chapterSentences(initial.storyId, initial.chapterId)
                    liveViews?.sentences = sentences
                }
            }
            ttsEngine.playbackState.collect { update ->
                val state = update.snapshot
                if (state == null) {
                    if (update.isAuthoritative && initial != null && navigator.current == AppRoute.Player) navigateBack()
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

/** The chapter's spoken sentences, numbered exactly as the engine numbers its chunks. */
private suspend fun ScreenHost.chapterSentences(
    storyId: String,
    chapterId: String,
): List<String> {
    val story = repository.story(storyId) ?: return emptyList()
    val chapter = story.chapters.firstOrNull { it.id == chapterId } ?: return emptyList()
    val html = withContext(Dispatchers.IO) { runCatching { repository.readChapter(chapter) }.getOrNull() } ?: chapter.content
    if (html.isNullOrBlank()) return emptyList()
    val rules = repository.regexRules.get()
    return withContext(Dispatchers.Default) { runCatching { TtsTextPreparation.prepareTtsChunks(html, rules) }.getOrDefault(emptyList()) }
}

private fun ScreenHost.buildNowPlaying(
    content: LinearLayout,
    state: TtsPlaybackSnapshot?,
): NowPlayingViews? {
    val story = state?.let { repository.story(it.storyId) }
    val backdrop = PlayerBackdrop(app)
    story?.let { s -> activeCoverSource(s)?.let { backdrop.load(it) { crossfade(false) } } }
    val column =
        LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(Spacing.LG), systemBarTop(), dp(Spacing.LG), dp(Spacing.XL))
        }
    column.addView(playerHeader(state))
    var views: NowPlayingViews? = null
    if (state == null) {
        column.addView(nothingPlaying(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
    } else {
        val wide = app.resources.configuration.screenWidthDp >= WIDE_MIN_DP
        val body =
            LinearLayout(app).apply {
                orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                gravity = Gravity.CENTER
            }
        story?.let { body.addView(playerArt(it, wide)) }
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
        val bodyWidth = if (wide) dp(WIDE_BODY_MAX_DP) else LinearLayout.LayoutParams.MATCH_PARENT
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
    val root =
        FrameLayout(app).apply {
            addView(backdrop, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(scroller, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
    content.addView(root, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
    views?.apply(state)
    return views
}

private fun ScreenHost.playerHeader(state: TtsPlaybackSnapshot?): View {
    val colors = ThemeManager.colors
    val voice =
        state
            ?.let { narration.store.pack(it.storyId, it.chapterId) }
            ?.recipe
            ?.voice
            ?.label
    return LinearLayout(app).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(HEADER_HEIGHT_DP)
        addView(app.iconButton(R.drawable.wna_chevron_down, "Collapse", colors.onSurface) { navigateBack() })
        val titles =
            LinearLayout(app).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(makeText(app, "Now Playing", Type.TITLE_SMALL, colors.onSurface))
                voice?.let { addView(makeText(app, "Voice: $it", Type.BODY_SMALL, colors.onSurfaceVariant)) }
            }
        addView(titles, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(
            app.iconButton(R.drawable.wna_graphic_eq, "Chapter audio", colors.onSurface) {
                val snapshot = ttsEngine.playbackState.value.snapshot
                if (snapshot != null) showNarrationStory(snapshot.storyId, snapshot.chapterId) else showNarration()
            },
        )
    }
}

private fun ScreenHost.playerArt(
    story: Story,
    wide: Boolean,
): View {
    val config = app.resources.configuration
    val widthDp =
        when {
            wide -> WIDE_COVER_DP
            config.screenHeightDp < SHORT_SCREEN_DP -> minOf(config.screenWidthDp * 42 / 100, SHORT_COVER_DP)
            else -> minOf(config.screenWidthDp * 56 / 100, COVER_MAX_DP)
        }
    val cover =
        coverImage(story, widthDp, widthDp * 3 / 2, tapToOpen = false).apply {
            contentDescription = "Open ${story.title}"
            setOnClickListener { showDetails(story.id) }
            elevation = dp(Spacing.LG).toFloat()
        }
    return FrameLayout(app).apply {
        setPadding(0, dp(Spacing.XS), 0, dp(Spacing.LG + 2))
        if (wide) setPadding(0, 0, dp(WIDE_GAP_DP), 0)
        clipToPadding = false
        addView(cover, FrameLayout.LayoutParams(dp(widthDp), dp(widthDp * 3 / 2), Gravity.CENTER))
    }
}

@Suppress("LongMethod") // One linear layout pass over the player's controls.
private fun ScreenHost.buildPlayerControls(
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
            setPadding(0, dp(Spacing.XS), 0, dp(2))
            if (story != null) {
                background = selectableRipple(colors.primary)
                setOnClickListener { showDetails(story.id) }
            }
        },
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { gravity = align },
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
    val caption = makeText(app, "", Type.BODY_LARGE, colors.onSurface).apply { maxLines = 3 }
    val captionCard = nowReadingCard(caption) { if (canReadAlong) openReadAlong(state) }
    controls.addView(captionCard)

    val seekBar = makePlayerSeekBar(app)
    controls.addView(seekBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    val elapsed = makeTimeLabel(app, Gravity.START)
    val remaining = makeTimeLabel(app, Gravity.END)
    controls.addView(
        LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(Spacing.LG), 0, dp(Spacing.LG), 0)
            addView(elapsed, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(remaining, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        },
    )

    val playPause = playButton()
    controls.addView(transportRow(playPause))

    lateinit var views: NowPlayingViews
    val rateChip =
        PlayerChip(app, R.drawable.wna_speed, rateLabel(state.rate)) {
            val rate =
                ttsEngine.playbackState.value.snapshot
                    ?.rate ?: state.rate
            TtsForegroundService.setRate(app, nextRatePreset(rate))
        }
    val sleepChip = PlayerChip(app, R.drawable.wna_bedtime, "Timer: Off") { views.showSleepTimerSheet() }
    val historyChip = PlayerChip(app, R.drawable.wna_history, "History") { views.showHistorySheet() }
    val stopChip = PlayerChip(app, R.drawable.wna_stop, "Stop") { TtsForegroundService.command(app, TtsForegroundService.ACTION_STOP) }
    val chips =
        WrapLayout(app).apply {
            horizontalSpacingDp = Spacing.SM
            verticalSpacingDp = Spacing.SM
            listOf(rateChip, sleepChip, historyChip, stopChip).forEach { addView(it.view) }
        }
    controls.addView(
        chips,
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { gravity = align },
    )
    if (canReadAlong) {
        controls.addView(
            makeButton(app, "Read along", Btn.TONAL, R.drawable.wna_menu_book) { openReadAlong(state) }.apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(Spacing.LG)
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
        )
    return views
}

/** Opens the playing chapter in the reader, which highlights and follows the spoken sentence. */
private fun ScreenHost.openReadAlong(state: TtsPlaybackSnapshot) {
    val live = ttsEngine.playbackState.value.snapshot ?: state
    // Readers of this story below Now Playing are dropped rather than returned to, so Back from the
    // reader lands here (an older one would also auto-jump to the playing chapter as soon as it
    // reopened, swallowing a Back press).
    navigator.bringToTop(AppRoute.Reader(live.storyId, live.chapterId)) {
        it is AppRoute.Reader && it.storyId == live.storyId
    }
    showReader(live.storyId, live.chapterId)
}

/** Nothing loaded: say so, and offer the saved place when there is one. */
private fun ScreenHost.nothingPlaying(): View {
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
    scope.launch {
        val positions = runCatching { repository.getTtsStoryPositions() }.getOrDefault(emptyList())
        val target = TtsContinueListening.target(positions) { repository.story(it) } ?: return@launch
        box.addView(
            makeButton(app, "Continue · ${target.chapter.title}", Btn.FILLED, R.drawable.wna_play) {
                playIfGenerated(target.story.id, target.chapter.id) { TtsForegroundService.start(app, target.story.id, target.chapter.id) }
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            },
        )
    }
    return box
}

private const val WIDE_MIN_DP = 720
private const val WIDE_BODY_MAX_DP = 860
private const val WIDE_COVER_DP = 300
private const val WIDE_GAP_DP = 48
private const val COVER_MAX_DP = 230
private const val SHORT_COVER_DP = 170
private const val SHORT_SCREEN_DP = 760
private const val HEADER_HEIGHT_DP = 64
private const val CHAPTER_TITLE_SP = 21f
