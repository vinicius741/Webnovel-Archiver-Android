package com.vinicius741.webnovelarchiver

import android.view.Gravity
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.core.ReaderContentRenderer
import com.vinicius741.webnovelarchiver.core.ReaderContentRenderer.ReaderDocumentColors
import com.vinicius741.webnovelarchiver.core.ScreenLayoutPlanning
import com.vinicius741.webnovelarchiver.core.StoryBookmarkPlanning
import com.vinicius741.webnovelarchiver.core.TextCleanup
import com.vinicius741.webnovelarchiver.core.readerSidePadding
import com.vinicius741.webnovelarchiver.ui.*

internal fun ScreenHost.showReader(storyId: String, chapterId: String) {
    val story = storage.getStory(storyId) ?: return
    val chapter = story.chapters.firstOrNull { it.id == chapterId } ?: return
    val currentIndex = story.chapters.indexOfFirst { it.id == chapter.id }
    // Re-render on fold/unfold/rotation so the reading column + side padding can reflow.
    rerender = { showReader(story.id, chapter.id) }
    val layout = currentScreenLayout()

    // Build the reader WebView + its live render function up front so the header action panels can
    // mutate the same `display` instance and re-render the WebView in place (live preview behind
    // the dialog) rather than each holding its own stale copy.
    val content = ReaderContentRenderer.contentOrUndownloadedMessage(storage.readChapter(chapter) ?: chapter.content)
    val formattedText = TextCleanup.htmlToFormattedText(content)
    val display = storage.getDisplayPreferences()
    val reader = WebView(app).apply {
        settings.javaScriptEnabled = false
        settings.domStorageEnabled = false
    }
    fun renderReader() {
        val readerColors = readerDocumentColors(display.readerDark)
        reader.setBackgroundColor(cssColorToInt(readerColors.background))
        reader.loadDataWithBaseURL(
            null,
            ReaderContentRenderer.document(chapter.title, content, display.readerFontScale, readerColors),
            "text/html",
            "utf-8",
            null,
        )
    }

    fun rebuild() = showReader(story.id, chapter.id)

    val bookmarkActive = story.lastReadChapterId == chapter.id
    val actions = listOf(
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
            showReaderTtsPanel(story, chapter)
        },
        AppBarAction(R.drawable.wna_more_vert, "Reader settings") {
            // The panel mutates the shared `display` and calls back into `renderReader`, so
            // font-size / dark-reader changes preview live on the WebView behind the dialog.
            showReaderSettingsPanel(
                display = display,
                onRerender = { renderReader() },
                onCopy = { copyToClipboard("Chapter text", formattedText); toast("Chapter copied") },
            )
        },
    )

    screen(
        title = sanitizeTitle(chapter.title),
        subtitle = "${currentIndex + 1} / ${story.chapters.size}",
        onBack = { showDetails(story.id) },
        actions = actions,
    ) {
        renderReader()
        // Center the WebView in a capped reading column (800dp max) with width-class side padding,
        // so text lines stay comfortable on the Fold's wide inner display instead of spanning edge
        // to edge. The column still fills all vertical space between the app bar and the nav bar.
        val sidePad = readerSidePadding(layout.widthClass)
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(sidePad), 0, dp(sidePad), 0)
        }
        column.addView(reader, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(MaxWidthFrameLayout(context).apply {
            // Cap the column width and center it; compact screens still measure at the parent width.
            maxContentWidthDp = ScreenLayoutPlanning.READER_COLUMN_MAX_WIDTH
            addView(column, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // Slim docked chapter navigation: keep the controls thumb-reachable without taking a large
        // bite out of the reading viewport.
        val navBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(ThemeManager.colors.elevation2)
            setPadding(dp(Spacing.MD), dp(Spacing.XS), dp(Spacing.MD), dp(Spacing.XS))
        }
        val hasPrev = currentIndex > 0
        val hasNext = currentIndex < story.chapters.lastIndex
        val progress = makeText(context, "${currentIndex + 1} / ${story.chapters.size}", Type.LABEL_MEDIUM, ThemeManager.colors.onSurfaceVariant).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        fun navButton(desc: String, icon: Int, enabled: Boolean, action: () -> Unit) =
            ImageView(context).apply {
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
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                    marginStart = dp(Spacing.XS)
                    marginEnd = dp(Spacing.XS)
                }
            }
        navBar.addView(navButton("Previous chapter", R.drawable.wna_skip_prev, hasPrev) { navigateChapter(story, chapter, -1) })
        navBar.addView(progress)
        navBar.addView(navButton("Next chapter", R.drawable.wna_skip_next, hasNext) { navigateChapter(story, chapter, 1) })
        addView(navBar)
    }
}

private fun readerDocumentColors(forceDark: Boolean): ReaderDocumentColors {
    val theme = ThemeManager.current
    return if (forceDark && !theme.isDark) {
        ReaderDocumentColors(background = "#121212", foreground = "#e6e6e6")
    } else {
        ReaderDocumentColors(
            background = theme.colors.background.toCssHex(),
            foreground = theme.colors.onBackground.toCssHex(),
        )
    }
}

private fun Int.toCssHex(): String = "#%06X".format(this and 0xFFFFFF)

private fun cssColorToInt(cssColor: String): Int =
    android.graphics.Color.parseColor(cssColor)
