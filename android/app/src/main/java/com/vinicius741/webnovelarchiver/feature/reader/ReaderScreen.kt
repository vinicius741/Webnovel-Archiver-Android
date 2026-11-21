package com.vinicius741.webnovelarchiver.feature.reader

import android.view.Gravity
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import com.vinicius741.webnovelarchiver.BuildConfig
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.data.diagnostics.ActivityCategory
import com.vinicius741.webnovelarchiver.data.diagnostics.ActivityLog
import com.vinicius741.webnovelarchiver.feature.narration.narration
import com.vinicius741.webnovelarchiver.feature.player.showPlayer
import com.vinicius741.webnovelarchiver.feature.settings.showTtsSettings
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.perf.PerfInstrumentation
import com.vinicius741.webnovelarchiver.platform.WebViewSafety
import com.vinicius741.webnovelarchiver.source.sanitizeTitle
import com.vinicius741.webnovelarchiver.ui.AppBarAction
import com.vinicius741.webnovelarchiver.ui.BottomSheetHandle
import com.vinicius741.webnovelarchiver.ui.MaxWidthFrameLayout
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.bindWhileAttached
import com.vinicius741.webnovelarchiver.ui.copyToClipboard
import com.vinicius741.webnovelarchiver.ui.currentScreenLayout
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.layout.ScreenLayoutPlanning
import com.vinicius741.webnovelarchiver.ui.layout.readerSidePadding
import com.vinicius741.webnovelarchiver.ui.makeEmptyState
import com.vinicius741.webnovelarchiver.ui.makeSheetRow
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.showBottomSheet
import com.vinicius741.webnovelarchiver.ui.tintedIcon
import com.vinicius741.webnovelarchiver.ui.toast

/** Opens a chapter in the reader, preparing it again (Back to it, a chapter change, a notification). */
internal fun ScreenHost.showReader(
    storyId: String,
    chapterId: String,
) {
    ActivityLog.record(ActivityCategory.READER, "chapter_opened", "storyId" to storyId, "chapterId" to chapterId)
    renderReader(AppRoute.Reader(storyId, chapterId), reload = true)
}

/** The reader route's model, kept while the route is in the history. Call inside the `screen { }` build. */
private fun ScreenHost.readerModel(route: AppRoute.Reader): ReaderScreenModel =
    screenModels.get(route.stableKey) {
        ReaderScreenModel(
            storyId = route.storyId,
            chapterId = route.chapterId,
            data = RepositoryReaderData(repository, narration, ttsEngine, readerDocumentPalette()),
            playback = ServiceReaderPlayback(this),
        )
    }

/**
 * Renders [route] from its model. A re-render ([reload] false: a fold) keeps a prepared chapter and
 * shows it at once; otherwise the chapter is prepared off the main thread behind a spinner.
 */
private fun ScreenHost.renderReader(
    route: AppRoute.Reader,
    reload: Boolean,
) {
    rerender = { renderReader(route, reload = false) }
    val kept = if (reload || navigator.current != route) null else screenModels.find<ReaderScreenModel>(route.stableKey)
    when (val page = kept?.state?.value?.page) {
        is ReaderPage.Ready -> ReaderRenderer(this, route, kept, page).build()
        ReaderPage.Missing ->
            renderReaderUnavailable(route, "Chapter unavailable", "This story or chapter is no longer in your library.")
        ReaderPage.Failed -> renderReaderUnavailable(route, "Could not open chapter", "Couldn't open this chapter.", retry = true)
        ReaderPage.Loading, null -> renderReaderLoading(route, reload)
    }
}

/** The spinner shown while the chapter is prepared; it re-renders once the model has a result. */
private fun ScreenHost.renderReaderLoading(
    route: AppRoute.Reader,
    reload: Boolean,
) {
    lateinit var model: ReaderScreenModel
    lateinit var body: View
    screen(route = route, title = "Reader", subtitle = "Preparing chapter…") {
        model = readerModel(route)
        model.open(reload)
        body = this
        addView(
            ProgressBar(context),
            LinearLayout
                .LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { gravity = Gravity.CENTER_HORIZONTAL },
        )
    }
    recordReaderPreparingForPerf(route.storyId, route.chapterId)
    // Bound to the spinner, so a result arriving after the user left cannot replace another screen.
    body.bindWhileAttached(scope, model.state) { state ->
        if (state.page != ReaderPage.Loading && body.isAttachedToWindow) renderReader(route, reload = false)
    }
}

/** Terminal Reader state for missing/failed preparation: explicit message + Back/Retry. */
private fun ScreenHost.renderReaderUnavailable(
    route: AppRoute.Reader,
    title: String,
    message: String,
    retry: Boolean = false,
) {
    screen(
        route = AppRoute.Reader(route.storyId, "unavailable"),
        title = "Reader",
        subtitle = title,
    ) {
        addView(
            makeEmptyState(
                context,
                title = title,
                message = message,
                iconRes = R.drawable.wna_menu_book,
                actionLabel = if (retry) "Retry" else null,
                onAction = if (retry) ({ showReader(route.storyId, route.chapterId) }) else null,
            ),
        )
    }
}

/**
 * One render of a prepared chapter, bound to its route's [ReaderScreenModel] while on screen. The
 * WebView and its [ReaderReadAlong] controller (highlight, following, chapter auto-follow, the
 * "Back to current sentence" pill) are rebuilt with each render; the bookmark button and the docked
 * player are patched from the model's state, and a new [ReaderPage] generation rebuilds the page.
 */
private class ReaderRenderer(
    private val host: ScreenHost,
    private val route: AppRoute.Reader,
    private val model: ReaderScreenModel,
    /** The page this render shows; another generation rebuilds it. */
    private val built: ReaderPage.Ready,
) {
    private val app = host.app
    private val document = built.document

    fun build() {
        val story = document.story
        val chapter = document.chapter
        val initial = model.state.value
        val layout = host.currentScreenLayout()
        val pageColor = cssColorToInt(document.colors.background)
        val reader =
            WebView(app).apply {
                // JS is safe only because the HTML was sanitized; file/content access stays locked down.
                WebViewSafety.applyReaderSettings(this, enableTtsHighlight = true)
                // Perf sessions only: onPageFinished marks actual content presentation.
                attachReaderPaintTracking(this)
                setBackgroundColor(pageColor)
                tag = READER_PAGE_TAG
            }

        // While this chapter is read aloud the bottom chrome swaps to the docked player: chapter
        // navigation is too easy to hit by accident and would break playback.
        val dock =
            ReaderPlayerDock(
                app,
                ReaderDockActions(
                    playPause = model::dockPlayPause,
                    back15 = model::skipBack,
                    cycleRate = model::cycleRate,
                    openPlayer = { host.showPlayer() },
                    close = model::stopPlayback,
                ),
            )
        lateinit var readAlong: ReaderReadAlong
        val pill = followPill(app) { readAlong.follow() }
        readAlong =
            ReaderReadAlong(
                host = host,
                page = reader,
                storyId = story.id,
                chapterId = chapter.id,
                initial = initial.playing,
                onChange = { pill.visibility = if (it.showsPill) View.VISIBLE else View.GONE },
            )

        lateinit var body: View
        lateinit var navHolder: View
        lateinit var dockHolder: View
        host.screen(
            route = route,
            title = sanitizeTitle(chapter.title),
            subtitle = "${document.chapterIndex + 1} / ${story.chapters.size} · ${story.title}",
            actions = actions(initial.bookmarked),
        ) {
            body = this
            setBackgroundColor(pageColor)
            reader.loadDataWithBaseURL(null, document.webViewHtml, "text/html", "utf-8", null)
            recordReaderPresentedForPerf()
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
                            setPadding(context.dp(sidePad), 0, context.dp(sidePad), 0)
                        },
                        FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
                    )
                    addView(
                        pill,
                        FrameLayout
                            .LayoutParams(
                                FrameLayout.LayoutParams.WRAP_CONTENT,
                                context.dp(PILL_HEIGHT_DP),
                                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
                            ).apply {
                                bottomMargin = context.dp(Spacing.MD)
                            },
                    )
                }
            addView(page, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

            val nav =
                readerChapterNav(
                    context,
                    story.chapters.size,
                    document.chapterIndex,
                    onPrevious = { openChapter(-1) },
                    onNext = { openChapter(1) },
                )
            navHolder = bottomDock(nav).also { addView(it) }
            dockHolder = bottomDock(dock.view).also { addView(it) }
            showDock(initial, dock, navHolder, dockHolder)
            pill.visibility = if (readAlong.state.showsPill) View.VISIBLE else View.GONE
        }

        var bookmarked = initial.bookmarked
        body.bindWhileAttached(host.scope, model.state) { state ->
            if (!body.isAttachedToWindow) return@bindWhileAttached
            val page = state.page
            if (page !is ReaderPage.Ready || page.generation != built.generation) {
                host.renderReader(route, reload = false)
                return@bindWhileAttached
            }
            if (state.bookmarked != bookmarked) {
                bookmarked = state.bookmarked
                // Restyle the button in place: re-rendering the whole reader rebuilt the WebView
                // and froze the screen for a moment.
                restyleBookmark(state.bookmarked)
            }
            showDock(state, dock, navHolder, dockHolder)
        }
        body.bindWhileAttached(host.scope, model.messages) { host.toast(it) }
        body.bindWhileAttached(host.scope, model.readAloudChoices) { showReadAloudChoice(it) }
    }

    private fun showDock(
        state: ReaderUiState,
        dock: ReaderPlayerDock,
        navHolder: View,
        dockHolder: View,
    ) {
        state.playing?.let(dock::applySnapshot)
        dockHolder.visibility = if (state.dockVisible) View.VISIBLE else View.GONE
        navHolder.visibility = if (state.dockVisible) View.GONE else View.VISIBLE
    }

    private fun openChapter(delta: Int) {
        model.chapterInDirection(delta)?.let { host.showReader(route.storyId, it) }
    }

    private fun restyleBookmark(active: Boolean) {
        val look = BookmarkLook(active)
        host.frame.findViewWithTag<ImageView>(READER_BOOKMARK_TAG)?.apply {
            setImageDrawable(context.tintedIcon(look.icon, look.tint ?: ThemeManager.colors.onSurface))
            contentDescription = look.label
        }
    }

    /** App-bar actions: bookmark (ivory when this chapter holds it), read aloud, reader settings. */
    private fun actions(bookmarked: Boolean): List<AppBarAction> {
        val bookmark = BookmarkLook(bookmarked)
        return listOf(
            AppBarAction(icon = bookmark.icon, label = bookmark.label, tint = bookmark.tint, tag = READER_BOOKMARK_TAG) {
                model.toggleBookmark()
            },
            AppBarAction(R.drawable.wna_headphones, "Listen") { model.startReadAloud() },
            AppBarAction(R.drawable.wna_text_fields, "Reader settings") { showSettings() },
        )
    }

    private fun showSettings() {
        var shown = model.state.value.display
        showReaderSettingsSheet(
            app,
            initial = shown,
            voiceSummary = model.voiceSummary(),
            onDisplay = { next ->
                val fontChanged = next.fontScale != shown.fontScale
                shown = next
                // The sheet outlives a chapter auto-advance, so it changes the reader shown at the
                // time, not the one that opened it.
                val target = shownReaderModel()
                (target ?: model).updateDisplay(next)
                if (fontChanged && target != null) {
                    val px = (BASE_FONT_PX * next.fontScale).toInt()
                    host.frame
                        .findViewWithTag<WebView>(READER_PAGE_TAG)
                        ?.evaluateJavascript("document.body.style.fontSize='${px}px';", null)
                }
            },
            onCopy = {
                host.copyToClipboard("Chapter text", document.formattedText)
                host.toast("Chapter copied")
            },
            onOpenVoiceSettings = { host.showTtsSettings() },
        )
    }

    /** The model of the reader on screen now, when it shows a chapter of this story. */
    private fun shownReaderModel(): ReaderScreenModel? =
        (host.navigator.current as? AppRoute.Reader)
            ?.takeIf { it.storyId == route.storyId }
            ?.let { host.screenModels.find<ReaderScreenModel>(it.stableKey) }

    /** "Continue listening" or "Listen from this chapter", when the saved place is in another chapter. */
    private fun showReadAloudChoice(choice: ReadAloudChoice) {
        val body = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL }
        lateinit var sheet: BottomSheetHandle
        body.addView(
            makeSheetRow(
                app,
                "Continue listening",
                supporting = "${choice.savedChapterTitle} · sentence ${choice.savedSentence}",
                iconRes = R.drawable.wna_history,
            ) {
                sheet.dismiss()
                model.continueListening(choice)
            },
        )
        body.addView(
            makeSheetRow(
                app,
                "Listen from this chapter",
                supporting = sanitizeTitle(document.chapter.title),
                iconRes = R.drawable.wna_play,
            ) {
                sheet.dismiss()
                model.listenFromThisChapter()
            },
        )
        sheet = showBottomSheet(app, title = "Listen", body = body)
    }
}

private const val READER_PAGE_TAG = "reader_page"
private const val READER_BOOKMARK_TAG = "reader_bookmark"
private const val BASE_FONT_PX = 18f

/** The bookmark button's look: filled ivory when this chapter holds the bookmark. */
private class BookmarkLook(
    active: Boolean,
) {
    val icon = if (active) R.drawable.wna_bookmark else R.drawable.wna_bookmark_outline
    val label = if (active) "Clear bookmark" else "Bookmark"
    val tint: Int? = ThemeManager.colors.bookmark.takeIf { active }
}

private fun recordReaderPreparingForPerf(
    storyId: String,
    chapterId: String,
) {
    if (BuildConfig.DEBUG) PerfInstrumentation.recordReaderPreparing(storyId, chapterId)
}

/** Release never sets a WebViewClient; debug sessions mark onPageFinished as content painted. */
private fun attachReaderPaintTracking(reader: WebView) {
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

private fun recordReaderPresentedForPerf() {
    if (BuildConfig.DEBUG) PerfInstrumentation.recordReaderPresented()
}
