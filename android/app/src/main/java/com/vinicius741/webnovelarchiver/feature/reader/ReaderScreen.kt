package com.vinicius741.webnovelarchiver.feature.reader

import android.view.Gravity
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.data.repository.clearTtsStoryPosition
import com.vinicius741.webnovelarchiver.data.repository.getTtsSession
import com.vinicius741.webnovelarchiver.data.repository.setChapterRewriteActive
import com.vinicius741.webnovelarchiver.domain.model.ChapterContentVersion
import com.vinicius741.webnovelarchiver.feature.ai.confirmChapterPolish
import com.vinicius741.webnovelarchiver.feature.details.showDetails
import com.vinicius741.webnovelarchiver.feature.settings.showTtsSettings
import com.vinicius741.webnovelarchiver.feature.story.navigateChapter
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.platform.WebViewSafety
import com.vinicius741.webnovelarchiver.source.sanitizeTitle
import com.vinicius741.webnovelarchiver.tts.TtsForegroundService
import com.vinicius741.webnovelarchiver.tts.TtsPlaybackSnapshot
import com.vinicius741.webnovelarchiver.tts.TtsPlaybackState
import com.vinicius741.webnovelarchiver.ui.AppBarAction
import com.vinicius741.webnovelarchiver.ui.MaxWidthFrameLayout
import com.vinicius741.webnovelarchiver.ui.ReaderChapterPolishControls
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.button
import com.vinicius741.webnovelarchiver.ui.copyToClipboard
import com.vinicius741.webnovelarchiver.ui.currentScreenLayout
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.layout.ScreenLayoutPlanning
import com.vinicius741.webnovelarchiver.ui.layout.readerSidePadding
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.showReaderSettingsPanel
import com.vinicius741.webnovelarchiver.ui.size
import com.vinicius741.webnovelarchiver.ui.tintedIcon
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal var activeReaderTtsStateJob: Job? = null

internal fun ScreenHost.detachReaderTtsListener() {
    activeReaderTtsStateJob?.cancel()
    activeReaderTtsStateJob = null
}

// Chunk indices refer to different text after a variant switch: restart a live session from the
// top; stop a paused/persisted one so it cannot resume mid-paragraph in the new variant.
internal fun ScreenHost.restartTtsForChapterVariant(
    storyId: String,
    chapterId: String,
) {
    val snapshot = ttsEngine.playbackState.value.snapshot
    val liveMatches = snapshot?.storyId == storyId && snapshot?.chapterId == chapterId
    val persisted = repository.getTtsSession()
    val persistedMatches = persisted?.storyId == storyId && persisted?.chapterId == chapterId
    when {
        // Variant switch remaps chunk indices: live playback restarts at the top, a persisted one is
        // stopped AND its story position forgotten so it cannot resume mid-paragraph in the new variant.
        liveMatches && snapshot?.isPaused == false -> TtsForegroundService.startFromChunk(app, storyId, chapterId, 0)
        liveMatches || persistedMatches -> {
            TtsForegroundService.stopForgettingPosition(app, storyId)
            scope.launch { repository.clearTtsStoryPosition(storyId) }
        }
    }
}

internal fun ScreenHost.showReader(
    storyId: String,
    chapterId: String,
) {
    detachReaderTtsListener()
    lastRequestedReaderChapterId = chapterId
    rerender = { showReader(storyId, chapterId) }
    val palette = ReaderDocumentPalette(normal = readerDocumentColors(false), forcedDark = readerDocumentColors(true))
    screen(
        route = AppRoute.Reader(storyId, chapterId),
        title = "Reader",
        subtitle = "Preparing chapter…",
        onBack = { showDetails(storyId) },
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
    screenObserver =
        scope.launch {
            // R12: missing ids, a failed read, and success each render a real state; delivery is
            // guarded by route identity so a late result cannot replace an unrelated screen.
            fun readerStillCurrent(): Boolean =
                navigator.current.let { it is AppRoute.Reader && it.storyId == storyId && it.chapterId == chapterId }

            when (val preparation = ReaderDocumentPreparer(repository).prepare(storyId, chapterId, palette)) {
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
                        timber.log.Timber.w(preparation.cause, "Reader preparation failed")
                        renderReaderUnavailable(
                            storyId,
                            "Could not open chapter",
                            preparation.cause.message ?: "The chapter file could not be read.",
                            retry = true,
                        )
                    }
            }
        }
}

/** Terminal Reader state for missing/failed preparation (R12): explicit message + Back/Retry. */
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
        onBack = { showDetails(storyId) },
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
    val formattedText = document.formattedText
    val display = document.display
    val layout = currentScreenLayout()
    val reader =
        WebView(app).apply {
            // JS is safe only because the HTML was sanitized; file/content access stays locked down.
            WebViewSafety.applyReaderSettings(this, enableTtsHighlight = true)
        }

    // Minimal JS-to-native surface: the injected script calls onTtsStart(index) on a double-tap.
    class ReaderTtsBridge {
        @JavascriptInterface
        fun onTtsStart(index: Int) {
            val clamped = index.coerceAtLeast(0)
            app.runOnUiThread {
                val latest = repository.story(story.id) ?: story
                val currentChapter = latest.chapters.firstOrNull { it.id == chapter.id } ?: return@runOnUiThread
                TtsForegroundService.startFromChunk(app, latest.id, currentChapter.id, clamped)
            }
        }
    }
    reader.addJavascriptInterface(ReaderTtsBridge(), "AndroidBridge")

    fun renderReader() {
        val readerColors = document.colors
        reader.setBackgroundColor(cssColorToInt(readerColors.background))
        reader.loadDataWithBaseURL(
            null,
            document.webViewHtml,
            "text/html",
            "utf-8",
            null,
        )
        // After a re-render, re-apply the live highlight so the speaking chunk stays marked.
        val liveSession = document.persistedSession
        if (liveSession != null && liveSession.storyId == story.id && liveSession.chapterId == chapter.id) {
            applyHighlight(reader, liveSession.currentChunkIndex, annotated.chunks.size)
        }
    }

    fun rebuild() = showReader(story.id, chapter.id)

    // Flips the manifest's active variant; the source chapter file is never modified.
    fun switchContentVersion() {
        val currentlyPolished = document.contentVersion == ChapterContentVersion.POLISHED
        scope.launch {
            repository.setChapterRewriteActive(story.id, chapter.id, !currentlyPolished)
            restartTtsForChapterVariant(story.id, chapter.id)
            toast(if (currentlyPolished) "Switched to source version" else "Switched to polished version")
            rebuild()
        }
    }

    val versionSuffix =
        when {
            document.contentVersion == ChapterContentVersion.POLISHED && document.contentStale -> " · Polished (out of date)"
            document.contentVersion == ChapterContentVersion.POLISHED -> " · Polished"
            document.hasAppliedRewrite -> " · Polished available"
            else -> ""
        }

    val bookmarkActive = story.lastReadChapterId == chapter.id
    val actions =
        listOf(
            AppBarAction(
                icon = if (bookmarkActive) R.drawable.wna_bookmark else R.drawable.wna_bookmark_outline,
                label = if (bookmarkActive) "Clear bookmark" else "Bookmark",
                tint = ThemeManager.colors.primary.takeIf { bookmarkActive },
            ) {
                scope.launch {
                    val updated = repository.toggleBookmark(story.id, chapter.id) ?: return@launch
                    toast(if (updated.lastReadChapterId == chapter.id) "Bookmarked" else "Bookmark cleared")
                    rebuild()
                }
            },
            AppBarAction(R.drawable.wna_speaker, "Read aloud") {
                TtsForegroundService.start(app, story.id, chapter.id)
            },
            AppBarAction(R.drawable.wna_more_vert, "Reader settings") {
                // The panel mutates `display` and re-renders for live preview; voice settings returns here on Back.
                showReaderSettingsPanel(
                    display = display,
                    onRerender = { showReader(story.id, chapter.id) },
                    onCopy = {
                        copyToClipboard("Chapter text", formattedText)
                        toast("Chapter copied")
                    },
                    onOpenVoiceSettings = {
                        showTtsSettings(onBack = { showReader(story.id, chapter.id) })
                    },
                    polishControls =
                        ReaderChapterPolishControls(
                            versionSwitchLabel =
                                if (document.contentVersion ==
                                    ChapterContentVersion.POLISHED
                                ) {
                                    "Switch to source version"
                                } else {
                                    "Switch to polished version"
                                },
                            onSwitchVersion = { switchContentVersion() },
                            polishLabel = "Polish this chapter…",
                            onPolish = { confirmChapterPolish(story, chapter) },
                        ).takeIf { document.hasAppliedRewrite },
                )
            },
        )

    // Replay + live snapshots drive the highlight and transport; other chapters' snapshots are ignored.
    var transportSnapshot: TtsPlaybackSnapshot? =
        currentReaderSnapshot(document.persistedSession, story.id, chapter.id, annotated.chunks.size)
    var transportPlayPause: ImageView? = null
    var transportBar: LinearLayout? = null
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
                transportSnapshot = relevant
                // Toggled (not add/remove) so a session starting after this build reveals the bar live.
                transportBar?.visibility = if (relevant != null) android.view.View.VISIBLE else android.view.View.GONE
                transportPlayPause?.let { button ->
                    val isPaused = relevant?.isPaused != false
                    button.setImageDrawable(
                        app.tintedIcon(
                            if (isPaused) R.drawable.wna_play else R.drawable.wna_pause,
                            ThemeManager.colors.primary,
                        ),
                    )
                    button.contentDescription = if (isPaused) "Play TTS" else "Pause TTS"
                }
                // Async + non-reloading, so no page flash; a null snapshot clears the highlight.
                applyHighlight(reader, relevant?.chunkIndex, relevant?.totalChunks ?: 0)
            }
        }

    screen(
        route = AppRoute.Reader(story.id, chapter.id),
        title = sanitizeTitle(chapter.title),
        subtitle = "${currentIndex + 1} / ${story.chapters.size}$versionSuffix",
        onSubtitleClick = if (document.hasAppliedRewrite) ({ switchContentVersion() }) else null,
        onBack = {
            // Detach before the next screen's disposeWebViews() tears down this WebView; playback continues.
            detachReaderTtsListener()
            showDetails(story.id)
        },
        actions = actions,
    ) {
        renderReader()
        val sidePad = readerSidePadding(layout.widthClass)
        val column =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(sidePad), 0, dp(sidePad), 0)
            }
        column.addView(reader, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(
            MaxWidthFrameLayout(context).apply {
                maxContentWidthDp = ScreenLayoutPlanning.READER_COLUMN_MAX_WIDTH
                addView(
                    column,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        Gravity.CENTER_HORIZONTAL,
                    ),
                )
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        addView(
            readerChapterNav(
                context,
                story.chapters.size,
                currentIndex,
                onPrevious = { navigateChapter(story, chapter, -1) },
                onNext = { navigateChapter(story, chapter, 1) },
            ),
        )

        // Docked above the nav; stays in the tree with toggled visibility so late sessions appear live.
        val transport =
            readerTtsTransport(
                snapshot = transportSnapshot,
                onPlayPause = { transportPlayPause = it },
                onPrev = { TtsForegroundService.command(app, TtsForegroundService.ACTION_PREVIOUS) },
                onPlayPauseTap = {
                    // Cold start seeds the transport from the persisted session, so a "playing"
                    // seed with no live engine session shows a phantom Pause icon — resume then.
                    val live = ttsEngine.playbackState.value.snapshot
                    val action =
                        if (live == null || transportSnapshot?.isPaused != false) {
                            TtsForegroundService.ACTION_RESUME_SESSION
                        } else {
                            TtsForegroundService.ACTION_PAUSE
                        }
                    TtsForegroundService.command(app, action)
                },
                onNext = { TtsForegroundService.command(app, TtsForegroundService.ACTION_NEXT) },
                onStop = { TtsForegroundService.command(app, TtsForegroundService.ACTION_STOP) },
            )
        transport.visibility = if (transportSnapshot != null) android.view.View.VISIBLE else android.view.View.GONE
        transportBar = transport
        addView(transport, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }
}
