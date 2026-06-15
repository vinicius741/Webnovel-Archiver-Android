package com.vinicius741.webnovelarchiver

import android.view.Gravity
import android.webkit.WebView
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.core.ReaderContentRenderer
import com.vinicius741.webnovelarchiver.core.StoryBookmarkPlanning
import com.vinicius741.webnovelarchiver.core.TextCleanup
import com.vinicius741.webnovelarchiver.ui.*

internal fun ScreenHost.showReader(storyId: String, chapterId: String) {
    val story = storage.getStory(storyId) ?: return
    val chapter = story.chapters.firstOrNull { it.id == chapterId } ?: return
    val currentIndex = story.chapters.indexOfFirst { it.id == chapter.id }
    // Opening a chapter implicitly marks it as the reading position (unchanged behaviour); the
    // header bookmark icon is the explicit toggle on top of this.
    val readerStory = StoryBookmarkPlanning.withBookmark(story, chapter.id, toggleExisting = false)
    storage.addOrUpdateStory(readerStory)

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
        reader.loadDataWithBaseURL(
            null,
            ReaderContentRenderer.document(chapter.title, content, display.readerFontScale, display.readerDark),
            "text/html",
            "utf-8",
            null,
        )
    }

    fun rebuild() = showReader(readerStory.id, chapter.id)

    val bookmarkActive = readerStory.lastReadChapterId == chapter.id
    val actions = listOf(
        AppBarAction(
            icon = R.drawable.wna_bookmark,
            label = if (bookmarkActive) "Clear bookmark" else "Bookmark",
            tint = ThemeManager.colors.primary.takeIf { bookmarkActive },
        ) {
            val latest = storage.getStory(readerStory.id) ?: readerStory
            val wasActive = latest.lastReadChapterId == chapter.id
            storage.addOrUpdateStory(StoryBookmarkPlanning.withBookmark(latest, chapter.id, toggleExisting = true))
            toast(if (wasActive) "Bookmark cleared" else "Bookmarked")
            rebuild()
        },
        AppBarAction(R.drawable.wna_speaker, "Read aloud") {
            showReaderTtsPanel(readerStory, chapter)
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
        subtitle = "${currentIndex + 1} / ${readerStory.chapters.size}",
        onBack = { showDetails(readerStory.id) },
        actions = actions,
    ) {
        renderReader()
        // Body is now the WebView only — it fills the area between the app bar and the docked
        // chapter-nav bar below, so the reading content is no longer squeezed under button rows.
        addView(reader, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // Docked chapter navigation: thumb-reachable Prev / Next pinned at the bottom of the screen.
        // Disabled at the story boundaries so the controls always communicate their availability.
        // The buttons are built with the standalone factory (not the `button {}` DSL) so they don't
        // auto-attach to the screen body — they're added straight into the nav bar with weighted
        // params so the pair reads symmetric and full-width.
        val navBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(ThemeManager.colors.elevation2)
            setPadding(dp(Spacing.MD), dp(Spacing.SM), dp(Spacing.MD), dp(Spacing.SM))
        }
        val hasPrev = currentIndex > 0
        val hasNext = currentIndex < readerStory.chapters.lastIndex
        fun navButton(label: String, icon: Int, enabled: Boolean, marginStartDp: Int, marginEndDp: Int, action: () -> Unit) =
            makeButton(context, label, Btn.TONAL, icon, action).apply {
                if (!enabled) disableButton(this)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(marginStartDp)
                    marginEnd = dp(marginEndDp)
                }
            }
        navBar.addView(navButton("Prev", R.drawable.wna_skip_prev, hasPrev, marginStartDp = 0, marginEndDp = Spacing.XS) { navigateChapter(readerStory, chapter, -1) })
        navBar.addView(navButton("Next", R.drawable.wna_skip_next, hasNext, marginStartDp = Spacing.XS, marginEndDp = 0) { navigateChapter(readerStory, chapter, 1) })
        addView(navBar)
    }
}
