package com.vinicius741.webnovelarchiver

import android.view.Gravity
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.vinicius741.webnovelarchiver.core.ChapterHtmlSanitizer
import com.vinicius741.webnovelarchiver.core.ReaderContentRenderer
import com.vinicius741.webnovelarchiver.core.ReaderContentRenderer.ReaderDocumentColors
import com.vinicius741.webnovelarchiver.core.ScreenLayoutPlanning
import com.vinicius741.webnovelarchiver.core.StoryBookmarkPlanning
import com.vinicius741.webnovelarchiver.core.TextCleanup
import com.vinicius741.webnovelarchiver.core.TtsPlaybackSnapshot
import com.vinicius741.webnovelarchiver.core.TtsPlaybackState
import com.vinicius741.webnovelarchiver.core.readerSidePadding
import com.vinicius741.webnovelarchiver.tts.TtsForegroundService
import com.vinicius741.webnovelarchiver.ui.*

/**
 * File-scope slot holding the reader screen's TTS state listener, so successive `showReader()`
 * renders (fold/rotate/settings) swap the listener (remove old → add new) instead of stacking
 * duplicates. Single-activity app → at most one reader is active at a time, so one slot suffices.
 * Set to `null` on reader teardown / activity destroy.
 */
internal var activeReaderTtsListener: ((TtsPlaybackSnapshot?) -> Unit)? = null

/** Detaches the active reader's TTS state listener (if any) and clears the slot. Called when the
 *  reader screen is left so its closure (which holds the reader WebView) can't fire into a torn-down
 *  view tree. Safe to call when no reader is active. */
internal fun ScreenHost.detachReaderTtsListener() {
    activeReaderTtsListener?.let { ttsEngine.removeStateListener(it) }
    activeReaderTtsListener = null
}

internal fun ScreenHost.showReader(
    storyId: String,
    chapterId: String,
) {
    val story = storage.getStory(storyId) ?: return
    val chapter = story.chapters.firstOrNull { it.id == chapterId } ?: return
    val currentIndex = story.chapters.indexOfFirst { it.id == chapter.id }
    // Re-render on fold/unfold/rotation so the reading column + side padding can reflow.
    rerender = { showReader(story.id, chapter.id) }
    val layout = currentScreenLayout()

    // Gap 3: sanitize the chapter HTML (strips scripts/on* handlers/active content) and produce the
    // TTS-annotated variant whose `data-tts-group` indices are byte-for-byte aligned with the chunk
    // list the TTS engine speaks. The sanitized annotated HTML is what the reader renders; the chunk
    // list is fed to the floating transport's progress label.
    val rawContent = ReaderContentRenderer.contentOrUndownloadedMessage(storage.readChapter(chapter) ?: chapter.content)
    val settings = storage.getTtsSettings()
    val annotated =
        TextCleanup.prepareTtsAnnotatedHtml(
            ChapterHtmlSanitizer.sanitize(rawContent),
            storage.getRegexRules(),
            settings.chunkSize,
        )
    val formattedText = TextCleanup.htmlToFormattedText(rawContent)
    val display = storage.getDisplayPreferences()
    val reader =
        WebView(app).apply {
            // R9 + gap 3: JS is enabled ONLY because the HTML was just sanitized; file/content access
            // stays locked down so the only script that runs is the highlight + tap-to-start one we
            // inject via [ReaderContentRenderer.document].
            WebViewSafety.applyReaderSettings(this, enableTtsHighlight = true)
        }

    /**
     * Single-method JavascriptInterface (gap 3 tap-to-start): the reader's injected script calls
     * `AndroidBridge.onTtsStart(groupIndex)` on a double-tap, and we hand the chunk index to the TTS
     * engine via [TtsForegroundService]. Kept deliberately minimal — one int parameter, no return —
     * so the JS↔native surface is as small as possible.
     */
    class ReaderTtsBridge {
        @JavascriptInterface
        fun onTtsStart(index: Int) {
            // Defensive: clamp + ignore taps when the index is out of the annotated chunk range.
            val clamped = index.coerceAtLeast(0)
            app.runOnUiThread {
                val latest = storage.getStory(story.id) ?: return@runOnUiThread
                val currentChapter = latest.chapters.firstOrNull { it.id == chapter.id } ?: return@runOnUiThread
                TtsForegroundService.start(app, latest.id, currentChapter.id, clamped)
            }
        }
    }
    reader.addJavascriptInterface(ReaderTtsBridge(), "AndroidBridge")

    fun renderReader() {
        val readerColors = readerDocumentColors(display.readerDark)
        reader.setBackgroundColor(cssColorToInt(readerColors.background))
        reader.loadDataWithBaseURL(
            null,
            ReaderContentRenderer.document(
                chapter.title,
                annotated.annotatedHtml,
                display.readerFontScale,
                readerColors,
                includeTtsScript = true,
            ),
            "text/html",
            "utf-8",
            null,
        )
        // After a re-render, re-apply the live highlight so the speaking chunk stays marked.
        val liveSession = storage.getTtsSession()
        if (liveSession != null && liveSession.storyId == story.id && liveSession.chapterId == chapter.id) {
            applyHighlight(reader, liveSession.currentChunkIndex, annotated.chunks.size)
        }
    }

    fun rebuild() = showReader(story.id, chapter.id)

    val bookmarkActive = story.lastReadChapterId == chapter.id
    val actions =
        listOf(
            AppBarAction(
                icon = if (bookmarkActive) R.drawable.wna_bookmark else R.drawable.wna_bookmark_outline,
                label = if (bookmarkActive) "Clear bookmark" else "Bookmark",
                tint = ThemeManager.colors.primary.takeIf { bookmarkActive },
            ) {
                val latest = storage.getStory(story.id) ?: story
                val wasActive = latest.lastReadChapterId == chapter.id
                storage.addOrUpdateStory(StoryBookmarkPlanning.withBookmark(latest, chapter.id, toggleExisting = true))
                toast(if (wasActive) "Bookmark cleared" else "Bookmarked")
                rebuild()
            },
            AppBarAction(R.drawable.wna_speaker, "Read aloud") {
                TtsForegroundService.start(app, story.id, chapter.id)
            },
            AppBarAction(R.drawable.wna_more_vert, "Reader settings") {
                // The panel mutates the shared `display` and calls back into `renderReader`, so
                // font-size / dark-reader changes preview live on the WebView behind the dialog.
                showReaderSettingsPanel(
                    display = display,
                    onRerender = { renderReader() },
                    onCopy = {
                        copyToClipboard("Chapter text", formattedText)
                        toast("Chapter copied")
                    },
                )
            },
        )

    // Gap 3 + 4: the engine emits a snapshot after every playback state change. We subscribe while
    // this reader screen is alive to (a) move the in-document highlight to the speaking chunk and
    // (b) refresh the floating transport's "Chunk X / Y" label + play/pause icon. Only snapshots
    // for *this* chapter are acted on, so navigating between chapters never cross-talks.
    //
    // The engine's listeners are multicast, and this single-activity app shows at most one reader
    // at a time, so we hold the active reader listener in a file-scope slot and swap it out on each
    // showReader() (remove old → add new). This avoids both clobbering the service's listener and
    // leaking a fresh listener per re-render (fold/rotate/settings).
    var transportSnapshot: TtsPlaybackSnapshot? = currentReaderSnapshot(story.id, chapter.id, annotated.chunks.size)
    var transportLabel: TextView? = null
    var transportPlayPause: ImageView? = null
    var transportBar: LinearLayout? = null
    activeReaderTtsListener?.let { ttsEngine.removeStateListener(it) }
    val readerListener: (TtsPlaybackSnapshot?) -> Unit = { snapshot ->
        app.runOnUiThread {
            val chapterToOpen = TtsPlaybackState.readerChapterTransition(story.id, chapter.id, snapshot)
            if (chapterToOpen != null && story.chapters.any { it.id == chapterToOpen }) {
                // Auto-advance changes the engine session before emitting its first snapshot for the
                // next chapter. Follow that transition by rebuilding the reader for the same chapter
                // the engine is now speaking; showReader swaps this listener out during the rebuild.
                showReader(story.id, chapterToOpen)
                return@runOnUiThread
            }
            val relevant = snapshot?.takeIf { it.storyId == story.id && it.chapterId == chapter.id }
            transportSnapshot = relevant
            // Gap 4 transport refresh. The bar is always present in the tree; we toggle its
            // visibility rather than adding/removing it, so a TTS session that starts after the
            // reader was built (the common case: open chapter → tap "Read aloud") reveals the bar
            // without needing a full screen rebuild.
            transportBar?.visibility = if (relevant != null) android.view.View.VISIBLE else android.view.View.GONE
            transportLabel?.text = readerTransportLabel(relevant)
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
            // Gap 3 highlight refresh — evaluateJavascript is async + non-reloading, so the page
            // doesn't flash. Only applied when there is a relevant snapshot (clear on stop/leave).
            applyHighlight(reader, relevant?.chunkIndex, relevant?.totalChunks ?: 0)
        }
    }
    activeReaderTtsListener = readerListener
    ttsEngine.addStateListener(readerListener)

    screen(
        title = sanitizeTitle(chapter.title),
        subtitle = "${currentIndex + 1} / ${story.chapters.size}",
        onBack = {
            // Leaving the reader: detach our TTS listener so it can't call evaluateJavascript on a
            // WebView that the next screen's disposeWebViews() will tear down. Playback itself
            // continues (driven by the foreground service) — only the in-reader observer is dropped.
            detachReaderTtsListener()
            showDetails(story.id)
        },
        actions = actions,
    ) {
        renderReader()
        // Center the WebView in a capped reading column (800dp max) with width-class side padding,
        // so text lines stay comfortable on the Fold's wide inner display instead of spanning edge
        // to edge. The column still fills all vertical space between the app bar and the nav bar.
        val sidePad = readerSidePadding(layout.widthClass)
        val column =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(sidePad), 0, dp(sidePad), 0)
            }
        column.addView(reader, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(
            MaxWidthFrameLayout(context).apply {
                // Cap the column width and center it; compact screens still measure at the parent width.
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

        // Slim docked chapter navigation: keep the controls thumb-reachable without taking a large
        // bite out of the reading viewport.
        val navBar =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(ThemeManager.colors.elevation2)
                setPadding(dp(Spacing.MD), dp(Spacing.XS), dp(Spacing.MD), dp(Spacing.XS))
            }
        val hasPrev = currentIndex > 0
        val hasNext = currentIndex < story.chapters.lastIndex
        val progress =
            makeText(
                context,
                "${currentIndex + 1} / ${story.chapters.size}",
                Type.LABEL_MEDIUM,
                ThemeManager.colors.onSurfaceVariant,
            ).apply {
                gravity = Gravity.CENTER
                includeFontPadding = false
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

        fun navButton(
            desc: String,
            icon: Int,
            enabled: Boolean,
            action: () -> Unit,
        ) = ImageView(context).apply {
            contentDescription = desc
            val tint = if (enabled) ThemeManager.colors.primary else ThemeManager.colors.onSurfaceVariant
            setImageDrawable(context.tintedIcon(icon, tint))
            alpha = if (enabled) 1f else 0.38f
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val pad = dp(Spacing.SM)
            setPadding(pad, pad, pad, pad)
            background = selectableRipple(ThemeManager.colors.onSurface)
            isEnabled = enabled
            isClickable = enabled
            isFocusable = enabled
            if (enabled) setOnClickListener { action() }
            layoutParams =
                LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                    marginStart = dp(Spacing.XS)
                    marginEnd = dp(Spacing.XS)
                }
        }
        navBar.addView(navButton("Previous chapter", R.drawable.wna_skip_prev, hasPrev) { navigateChapter(story, chapter, -1) })
        navBar.addView(progress)
        navBar.addView(navButton("Next chapter", R.drawable.wna_skip_next, hasNext) { navigateChapter(story, chapter, 1) })
        addView(navBar)

        // Gap 4: floating TTS transport, docked just above the chapter nav. The bar is ALWAYS added
        // to the tree and its visibility is toggled (VISIBLE while a session for THIS chapter exists,
        // GONE otherwise). Toggling — rather than add/remove — is what lets a TTS session that starts
        // AFTER the reader was built (open chapter → tap "Read aloud") reveal
        // the transport live via the state listener, without rebuilding the whole screen.
        val transport =
            readerTtsTransport(
                snapshot = transportSnapshot,
                onLabel = { transportLabel = it },
                onPlayPause = { transportPlayPause = it },
                onPrev = { TtsForegroundService.command(app, TtsForegroundService.ACTION_PREVIOUS) },
                onPlayPauseTap = {
                    val action =
                        if (transportSnapshot?.isPaused != false) {
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

private fun readerDocumentColors(forceDark: Boolean): ReaderDocumentColors {
    val theme = ThemeManager.current
    val accent = ThemeManager.colors.primary.toCssHex()
    return if (forceDark && !theme.isDark) {
        ReaderDocumentColors(background = "#121212", foreground = "#e6e6e6", ttsHighlight = accent)
    } else {
        ReaderDocumentColors(
            background = theme.colors.background.toCssHex(),
            foreground = theme.colors.onBackground.toCssHex(),
            ttsHighlight = accent,
        )
    }
}

private fun Int.toCssHex(): String = "#%06X".format(this and 0xFFFFFF)

private fun cssColorToInt(cssColor: String): Int = android.graphics.Color.parseColor(cssColor)

/**
 * Builds the initial transport snapshot for [showReader] from any persisted TTS session, so the
 * floating transport + highlight reflect "already playing this chapter" on entry. Returns null when
 * there is no session or it targets a different chapter.
 */
private fun ScreenHost.currentReaderSnapshot(
    storyId: String,
    chapterId: String,
    chunkCount: Int,
): TtsPlaybackSnapshot? {
    val session = storage.getTtsSession() ?: return null
    if (session.storyId != storyId || session.chapterId != chapterId) return null
    return TtsPlaybackState.snapshotForSession(
        session = session,
        // On entry we don't know the engine's live chunk count for the persisted session, so fall back
        // to the rendered chapter's chunk count; the first state-change callback corrects it.
        totalChunks = chunkCount,
        isPlaying = !session.isPaused,
    )
}

/** Transport label for the floating TTS bar: "Chunk X / Y", "Buffering", or "Ready". */
private fun readerTransportLabel(snapshot: TtsPlaybackSnapshot?): String =
    if (snapshot == null) {
        "Ready"
    } else if (snapshot.totalChunks <= 0) {
        "Buffering"
    } else {
        TtsPlaybackState.chunkProgress(snapshot.chunkIndex, snapshot.totalChunks)
    }

/**
 * Drives the in-document highlight (gap 3) by evaluating the reader script's `WnaTts.setActive(i)`.
 * `chunkIndex == null` clears the highlight (playback stopped / different chapter). Cheap and
 * non-reloading: evaluateJavascript does not re-render the WebView, so there's no page flash.
 */
private fun applyHighlight(
    reader: WebView,
    chunkIndex: Int?,
    totalChunks: Int,
) {
    if (totalChunks <= 0 && chunkIndex == null) {
        reader.evaluateJavascript("if(window.WnaTts){WnaTts.setActive(null);}", null)
        return
    }
    val safeIndex = chunkIndex ?: return
    reader.evaluateJavascript("if(window.WnaTts){WnaTts.setActive($safeIndex);}", null)
}

/**
 * Gap 4 floating TTS transport (mirrors the legacy RN `TTSController`). A slim bar with a chunk-
 * progress label and Prev / Play-Pause / Next / Stop buttons. The label + play-pause icon are
 * exposed via callbacks so [showReader]'s state listener can swap them in place without rebuilding
 * the screen on every chunk advance.
 */
private fun ScreenHost.readerTtsTransport(
    snapshot: TtsPlaybackSnapshot?,
    onLabel: (TextView) -> Unit,
    onPlayPause: (ImageView) -> Unit,
    onPrev: () -> Unit,
    onPlayPauseTap: () -> Unit,
    onNext: () -> Unit,
    onStop: () -> Unit,
): LinearLayout {
    val colors = ThemeManager.colors
    val bar =
        LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(colors.elevation1)
            setPadding(dp(Spacing.MD), dp(Spacing.XS), dp(Spacing.MD), dp(Spacing.XS))
        }

    fun transportIcon(
        desc: String,
        icon: Int,
        action: () -> Unit,
    ) = ImageView(app).apply {
        contentDescription = desc
        setImageDrawable(app.tintedIcon(icon, colors.primary))
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        val pad = dp(Spacing.SM)
        setPadding(pad, pad, pad, pad)
        background = selectableRipple(colors.onSurface)
        setOnClickListener { action() }
        layoutParams =
            LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                marginStart = dp(Spacing.XS)
                marginEnd = dp(Spacing.XS)
            }
    }
    bar.addView(transportIcon("Previous chunk", R.drawable.wna_skip_prev, onPrev))
    val label =
        makeText(app, readerTransportLabel(snapshot), Type.LABEL_MEDIUM, colors.onSurface).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
    onLabel(label)
    bar.addView(label)
    val isPaused = snapshot?.isPaused != false
    val playPause =
        transportIcon(
            if (isPaused) "Play TTS" else "Pause TTS",
            if (isPaused) R.drawable.wna_play else R.drawable.wna_pause,
            onPlayPauseTap,
        )
    onPlayPause(playPause)
    bar.addView(playPause)
    bar.addView(transportIcon("Next chunk", R.drawable.wna_skip_next, onNext))
    bar.addView(transportIcon("Stop TTS", R.drawable.wna_stop, onStop))
    return bar
}
