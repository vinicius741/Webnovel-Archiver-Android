package com.vinicius741.webnovelarchiver.feature.reader

import android.view.Gravity
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
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
import com.vinicius741.webnovelarchiver.feature.player.nextRatePreset
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
import com.vinicius741.webnovelarchiver.ui.layout.ScreenLayoutPlanning
import com.vinicius741.webnovelarchiver.ui.layout.readerSidePadding
import com.vinicius741.webnovelarchiver.ui.makeSheetRow
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.ripple
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.showBottomSheet
import com.vinicius741.webnovelarchiver.ui.showReaderSettingsPanel
import com.vinicius741.webnovelarchiver.ui.tintedIcon
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

internal var activeReaderTtsStateJob: Job? = null

/** The page of the reader rendered last, so a sheet left open across a re-render reaches the new one. */
private var activeReaderPage: WeakReference<WebView>? = null

internal fun ScreenHost.detachReaderTtsListener() {
    activeReaderTtsStateJob?.cancel()
    activeReaderTtsStateJob = null
}

internal fun ScreenHost.showReader(
    storyId: String,
    chapterId: String,
) {
    detachReaderTtsListener()
    lastRequestedReaderChapterId = chapterId
    ActivityLog.record(ActivityCategory.READER, "chapter_opened", "storyId" to storyId, "chapterId" to chapterId)
    rerender = { showReader(storyId, chapterId) }
    val palette = readerDocumentPalette()
    screen(
        route = AppRoute.Reader(storyId, chapterId),
        title = "Reader",
        subtitle = "Preparing chapter…",
    ) {
        addView(
            ProgressBar(context),
            LinearLayout
                .LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { gravity = Gravity.CENTER_HORIZONTAL },
        )
    }
    recordReaderPreparingForPerf(storyId, chapterId)
    screenObserver =
        scope.launch {
            // R12: missing ids, a failed read, and success each render a real state; delivery is
            // guarded by route identity so a late result cannot replace an unrelated screen.
            fun readerStillCurrent(): Boolean =
                navigator.current.let { it is AppRoute.Reader && it.storyId == storyId && it.chapterId == chapterId }

            when (val preparation = prepareReaderDocumentWithPerf(storyId, chapterId, palette)) {
                is ReaderPreparation.Ready ->
                    if (readerStillCurrent()) renderPreparedReader(preparation.document)
                ReaderPreparation.Missing ->
                    if (readerStillCurrent()) {
                        renderReaderUnavailable(
                            storyId,
                            "Chapter unavailable",
                            "This story or chapter is no longer in your library.",
                        )
                    }
                is ReaderPreparation.Failed ->
                    if (readerStillCurrent()) {
                        // IOException messages can embed absolute file paths; the detail stays in logs only.
                        timber.log.Timber.w(preparation.cause, "Reader preparation failed")
                        renderReaderUnavailable(
                            storyId,
                            "Could not open chapter",
                            "Couldn't open this chapter.",
                            retry = true,
                        )
                    }
            }
        }
}

/** Terminal Reader state for missing/failed preparation: explicit message + Back/Retry. */
private fun ScreenHost.renderReaderUnavailable(
    storyId: String,
    title: String,
    message: String,
    retry: Boolean = false,
) {
    screen(
        route = AppRoute.Reader(storyId, "unavailable"),
        title = "Reader",
        subtitle = title,
    ) {
        addView(
            com.vinicius741.webnovelarchiver.ui.makeEmptyState(
                context,
                title = title,
                message = message,
                iconRes = R.drawable.wna_menu_book,
                actionLabel = if (retry) "Retry" else null,
                onAction = if (retry) ({ showReader(storyId, lastRequestedReaderChapterId ?: return@makeEmptyState) }) else null,
            ),
        )
    }
}

/** The chapter id of the most recent Reader request, so Retry can re-issue it after a failure. */
internal var lastRequestedReaderChapterId: String? = null

private fun ScreenHost.renderPreparedReader(document: ReaderDocument) {
    val story = document.story
    val chapter = document.chapter
    val currentIndex = document.chapterIndex
    val annotated = document.annotated
    val display = document.display
    val layout = currentScreenLayout()
    val pageColor = cssColorToInt(document.colors.background)
    val reader =
        WebView(app).apply {
            // JS is safe only because the HTML was sanitized; file/content access stays locked down.
            WebViewSafety.applyReaderSettings(this, enableTtsHighlight = true)
            // Perf sessions only: onPageFinished marks actual content presentation.
            attachReaderPaintTracking(this)
            setBackgroundColor(pageColor)
        }
    activeReaderPage = WeakReference(reader)

    // Replay + live snapshots drive the highlight and transport; other chapters' snapshots are ignored.
    var transportSnapshot: TtsPlaybackSnapshot? =
        currentReaderSnapshot(document.persistedSession, story.id, chapter.id, annotated.chunks.size)
    var following = true
    val pill = followPill { reader.evaluateJavascript("if(window.WnaTts&&WnaTts.follow){WnaTts.follow();}", null) }

    fun refreshPill() {
        val snapshot = transportSnapshot
        pill.visibility = if (!following && snapshot != null && !snapshot.isPaused) View.VISIBLE else View.GONE
    }

    val ttsBridge =
        ReaderTtsBridge(
            this,
            story,
            chapter,
            onFollowChanged = { value ->
                following = value
                refreshPill()
            },
        )
    ttsBridge.activeChunk = transportSnapshot?.chunkIndex ?: -1
    reader.addJavascriptInterface(ttsBridge, "AndroidBridge")

    // While this chapter is read aloud the bottom chrome swaps to the docked player: chapter
    // navigation is too easy to hit by accident and would break playback.
    val dock =
        ReaderPlayerDock(
            app,
            ReaderDockActions(
                playPause = {
                    // Cold start seeds the dock from the persisted session, so a "playing" seed with no
                    // live engine session shows a phantom Pause icon — resume then.
                    val live = ttsEngine.playbackState.value.snapshot
                    val action =
                        if (live == null || transportSnapshot?.isPaused != false) {
                            TtsForegroundService.ACTION_RESUME_SESSION
                        } else {
                            TtsForegroundService.ACTION_PAUSE
                        }
                    TtsForegroundService.command(app, action)
                },
                back15 = { TtsForegroundService.command(app, TtsForegroundService.ACTION_SKIP_BACK) },
                cycleRate = {
                    val rate =
                        ttsEngine.playbackState.value.snapshot
                            ?.rate ?: transportSnapshot?.rate ?: 1f
                    TtsForegroundService.setRate(app, nextRatePreset(rate))
                },
                openPlayer = { showPlayer() },
                close = { TtsForegroundService.command(app, TtsForegroundService.ACTION_STOP) },
            ),
        )
    transportSnapshot?.let(dock::applySnapshot)
    var dockHolder: View? = null
    var navHolder: View? = null

    fun applyBottomChrome(ttsActive: Boolean) {
        dockHolder?.visibility = if (ttsActive) View.VISIBLE else View.GONE
        navHolder?.visibility = if (ttsActive) View.GONE else View.VISIBLE
        refreshPill()
    }
    activeReaderTtsStateJob?.cancel()
    activeReaderTtsStateJob =
        scope.launch {
            ttsEngine.playbackState.collect { update ->
                val snapshot = update.snapshot
                // Auto-follow only while this reader chapter is the screen the user is looking at —
                // otherwise a chapter transition elsewhere (player skip, background auto-advance)
                // would hijack whatever screen is currently open.
                val route = navigator.current
                val readerIsCurrent =
                    route is AppRoute.Reader && route.storyId == story.id && route.chapterId == chapter.id
                val chapterToOpen = TtsPlaybackState.readerChapterTransition(story.id, chapter.id, snapshot)
                if (readerIsCurrent && chapterToOpen != null && story.chapters.any { it.id == chapterToOpen }) {
                    // Auto-advance swaps the session before the next chapter's first snapshot: rebuild to follow.
                    showReader(story.id, chapterToOpen)
                    return@collect
                }
                val relevant =
                    TtsPlaybackState.snapshotAfterUpdate(
                        current = transportSnapshot,
                        update = update,
                        storyId = story.id,
                        chapterId = chapter.id,
                    )
                // A session that starts (again) in this chapter follows the spoken sentence afresh.
                if (transportSnapshot == null && relevant != null && !following) {
                    reader.evaluateJavascript("if(window.WnaTts&&WnaTts.follow){WnaTts.follow();}", null)
                }
                transportSnapshot = relevant
                ttsBridge.activeChunk = relevant?.chunkIndex ?: -1
                // Toggled (not add/remove) so a session starting after this build swaps the chrome live.
                if (relevant != null) dock.applySnapshot(relevant)
                applyBottomChrome(relevant != null)
                // Async + non-reloading, so no page flash; a null snapshot clears the highlight.
                applyHighlight(reader, relevant?.chunkIndex, relevant?.totalChunks ?: 0)
            }
        }

    screen(
        route = AppRoute.Reader(story.id, chapter.id),
        title = sanitizeTitle(chapter.title),
        subtitle = "${currentIndex + 1} / ${story.chapters.size} · ${story.title}",
        actions = readerActions(document),
    ) {
        setBackgroundColor(pageColor)
        reader.loadDataWithBaseURL(null, document.webViewHtml, "text/html", "utf-8", null)
        recordReaderPresentedForPerf()
        // The initial highlight is pulled by the page itself through ttsBridge.activeTtsGroup().
        val sidePad = readerSidePadding(layout.widthClass)
        val page =
            FrameLayout(context).apply {
                addView(
                    MaxWidthFrameLayout(context).apply {
                        maxContentWidthDp = ScreenLayoutPlanning.READER_COLUMN_MAX_WIDTH
                        addView(
                            reader,
                            FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                Gravity.CENTER_HORIZONTAL,
                            ),
                        )
                        setPadding(dp(sidePad), 0, dp(sidePad), 0)
                    },
                    FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
                )
                addView(
                    pill,
                    FrameLayout
                        .LayoutParams(
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            dp(PILL_HEIGHT_DP),
                            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
                        ).apply {
                            bottomMargin = dp(Spacing.MD)
                        },
                )
            }
        addView(page, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val nav =
            readerChapterNav(
                context,
                story.chapters.size,
                currentIndex,
                onPrevious = { navigateChapter(story, chapter, -1) },
                onNext = { navigateChapter(story, chapter, 1) },
            )
        navHolder = bottomDock(nav).also { addView(it) }
        dockHolder = bottomDock(dock.view).also { addView(it) }
        applyBottomChrome(transportSnapshot != null)
    }
}

/** App-bar actions: bookmark (ivory when this chapter holds it), read aloud, reader settings. */
private fun ScreenHost.readerActions(document: ReaderDocument): List<AppBarAction> {
    val story = document.story
    val chapter = document.chapter
    val bookmarkActive = story.lastReadChapterId == chapter.id
    return listOf(
        AppBarAction(
            icon = if (bookmarkActive) R.drawable.wna_bookmark else R.drawable.wna_bookmark_outline,
            label = if (bookmarkActive) "Clear bookmark" else "Bookmark",
            tint = ThemeManager.colors.bookmark.takeIf { bookmarkActive },
        ) {
            scope.launch {
                val updated = repository.toggleBookmark(story.id, chapter.id) ?: return@launch
                toast(if (updated.lastReadChapterId == chapter.id) "Bookmarked" else "Bookmark cleared")
                showReader(story.id, chapter.id)
            }
        },
        AppBarAction(R.drawable.wna_headphones, "Read aloud") { startReadAloud(story, chapter) },
        AppBarAction(R.drawable.wna_text_fields, "Reader settings") {
            val voice = runCatching { narration.currentVoice().label }.getOrNull()
            val rate =
                ttsEngine.playbackState.value.snapshot
                    ?.rate ?: repository.ttsSettings.get().rate
            showReaderSettingsPanel(
                display = document.display,
                voiceSummary = listOfNotNull(voice, rateLabel(rate)).joinToString(" · "),
                // The sheet outlives a re-render (Pure black, a chapter auto-advance), so both
                // callbacks act on the reader shown at the time, not the one that opened the sheet.
                onFontScale = { scale ->
                    val px = (BASE_FONT_PX * scale).toInt()
                    if (readerOfStory(story) != null) {
                        activeReaderPage?.get()?.evaluateJavascript("document.body.style.fontSize='${px}px';", null)
                    }
                },
                onRerender = { readerOfStory(story)?.let { showReader(it.storyId, it.chapterId) } },
                onCopy = {
                    copyToClipboard("Chapter text", document.formattedText)
                    toast("Chapter copied")
                },
                onOpenVoiceSettings = { showTtsSettings() },
            )
        },
    )
}

/** The reader route shown now when it is one of [story]'s chapters. */
private fun ScreenHost.readerOfStory(story: Story): AppRoute.Reader? =
    (navigator.current as? AppRoute.Reader)?.takeIf { route ->
        route.storyId == story.id && story.chapters.any { it.id == route.chapterId }
    }

/**
 * Minimal JS-to-native surface: the injected script calls onTtsStart(index) on a double-tap,
 * onFollowChanged when the reader drags away from (or back to) the spoken sentence, and
 * onNextChapter from the end-of-chapter card; it reads activeTtsGroup() once the page has loaded,
 * since highlights sent before then are lost.
 */
private class ReaderTtsBridge(
    private val host: ScreenHost,
    private val story: Story,
    private val chapter: Chapter,
    private val onFollowChanged: (Boolean) -> Unit,
) {
    /** Written on the main thread by the playback collector; read on the WebView's bridge thread. */
    @Volatile
    var activeChunk: Int = -1

    @JavascriptInterface
    fun activeTtsGroup(): Int = activeChunk

    @JavascriptInterface
    fun onTtsStart(index: Int) {
        val clamped = index.coerceAtLeast(0)
        host.app.runOnUiThread {
            val latest = host.repository.story(story.id) ?: story
            val currentChapter = latest.chapters.firstOrNull { it.id == chapter.id } ?: return@runOnUiThread
            host.playIfGenerated(latest.id, currentChapter.id) {
                TtsForegroundService.startFromChunk(host.app, latest.id, currentChapter.id, clamped)
            }
        }
    }

    @JavascriptInterface
    fun onFollowChanged(following: Boolean) {
        host.app.runOnUiThread { onFollowChanged.invoke(following) }
    }

    @JavascriptInterface
    fun onNextChapter() {
        host.app.runOnUiThread {
            val route = host.navigator.current
            if (route !is AppRoute.Reader || route.storyId != story.id || route.chapterId != chapter.id) return@runOnUiThread
            // With this story's session loaded the reader follows the spoken chapter, so opening the
            // next one directly would bounce back; move the session instead (it keeps a pause).
            if (host.ttsEngine.playbackState.value.snapshot
                    ?.storyId == story.id
            ) {
                TtsForegroundService.command(host.app, TtsForegroundService.ACTION_NEXT_CHAPTER)
            } else {
                host.navigateChapter(host.repository.story(story.id) ?: story, chapter, 1)
            }
        }
    }
}

/**
 * "Continue" and "listen from here" only differ when the saved place is in another chapter, so only
 * then does the listener get asked (in a bottom sheet).
 */
private fun ScreenHost.startReadAloud(
    story: Story,
    chapter: Chapter,
) {
    scope.launch {
        val position = repository.getTtsStoryPosition(story.id)
        val saved = TtsSessionPlanning.resolveStartPosition(story, chapter.id, position)
        if (saved.chapterId == chapter.id) {
            playIfGenerated(story.id, chapter.id) { TtsForegroundService.start(app, story.id, chapter.id) }
            return@launch
        }
        val savedTitle =
            story.chapters
                .firstOrNull { it.id == saved.chapterId }
                ?.title
                .orEmpty()
        val body = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL }
        lateinit var sheet: BottomSheetHandle
        body.addView(
            makeSheetRow(
                app,
                "Continue listening",
                supporting = "$savedTitle · sentence ${saved.chunkIndex + 1}",
                iconRes = R.drawable.wna_history,
            ) {
                sheet.dismiss()
                playIfGenerated(story.id, saved.chapterId) { TtsForegroundService.start(app, story.id, chapter.id) }
            },
        )
        body.addView(
            makeSheetRow(app, "Listen from this chapter", supporting = sanitizeTitle(chapter.title), iconRes = R.drawable.wna_play) {
                sheet.dismiss()
                playIfGenerated(story.id, chapter.id) { TtsForegroundService.startFromChunk(app, story.id, chapter.id, 0) }
            },
        )
        sheet = showBottomSheet(app, title = "Read aloud", body = body)
    }
}

private const val BASE_FONT_PX = 18f

internal fun ScreenHost.recordReaderPreparingForPerf(
    storyId: String,
    chapterId: String,
) {
    if (BuildConfig.DEBUG) PerfInstrumentation.recordReaderPreparing(storyId, chapterId)
}

internal suspend fun ScreenHost.prepareReaderDocumentWithPerf(
    storyId: String,
    chapterId: String,
    palette: ReaderDocumentPalette,
): ReaderPreparation {
    val startedNanos = if (BuildConfig.DEBUG) System.nanoTime() else 0L
    val preparation = ReaderDocumentPreparer(repository).prepare(storyId, chapterId, palette)
    if (BuildConfig.DEBUG) {
        val outcome =
            when (preparation) {
                is ReaderPreparation.Ready -> "ready"
                ReaderPreparation.Missing -> "missing"
                is ReaderPreparation.Failed -> "failed"
            }
        PerfInstrumentation.recordReaderPrepareDone(outcome, (System.nanoTime() - startedNanos) / 1_000_000)
    }
    return preparation
}

/** Release never sets a WebViewClient; debug sessions mark onPageFinished as content painted. */
internal fun attachReaderPaintTracking(reader: WebView) {
    if (!BuildConfig.DEBUG) return
    reader.webViewClient =
        object : WebViewClient() {
            override fun onPageFinished(
                view: WebView?,
                url: String?,
            ) {
                PerfInstrumentation.recordReaderPainted()
            }
        }
}

internal fun recordReaderPresentedForPerf() {
    if (BuildConfig.DEBUG) PerfInstrumentation.recordReaderPresented()
}
