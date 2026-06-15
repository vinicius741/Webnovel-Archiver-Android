package com.vinicius741.webnovelarchiver

import android.webkit.WebView
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.core.PreferenceNormalization
import com.vinicius741.webnovelarchiver.core.ReaderContentRenderer
import com.vinicius741.webnovelarchiver.core.StoryBookmarkPlanning
import com.vinicius741.webnovelarchiver.core.TextCleanup
import com.vinicius741.webnovelarchiver.tts.TtsForegroundService
import com.vinicius741.webnovelarchiver.ui.*

internal fun ScreenHost.showReader(storyId: String, chapterId: String) {
    val story = storage.getStory(storyId) ?: return
    val chapter = story.chapters.firstOrNull { it.id == chapterId } ?: return
    val currentIndex = story.chapters.indexOfFirst { it.id == chapter.id }
    val readerStory = StoryBookmarkPlanning.withBookmark(story, chapter.id, toggleExisting = false)
    storage.addOrUpdateStory(readerStory)
    // R5: sanitize the app-bar title so trailing ellipsis noise doesn't leak (matches chapter list).
    screen(title = sanitizeTitle(chapter.title), subtitle = "${currentIndex + 1} / ${readerStory.chapters.size}", onBack = { showDetails(readerStory.id) }) {
        // R1: Row 1 — chapter navigation (Prev / Next), separated from TTS transport.
        grid(columns = 2) {
            button("Prev", Btn.TONAL, R.drawable.wna_skip_prev) { navigateChapter(readerStory, chapter, -1) }
            button("Next", Btn.TONAL, R.drawable.wna_skip_next) { navigateChapter(readerStory, chapter, 1) }
        }
        // R1 + R2: Row 2 — TTS transport. The start button is always available; the transport
        // controls only render when a session is active so they aren't permanent noise.
        flow {
            button("TTS", Btn.TONAL, R.drawable.wna_speaker) { TtsForegroundService.start(app, readerStory.id, chapter.id) }
            if (storage.getTtsSession() != null) {
                button("Pause", Btn.TEXT, R.drawable.wna_pause) { TtsForegroundService.command(app, TtsForegroundService.ACTION_PAUSE) }
                button("Stop", Btn.TEXT, R.drawable.wna_stop) { TtsForegroundService.command(app, TtsForegroundService.ACTION_STOP) }
                button("TTS Prev", Btn.TEXT, R.drawable.wna_skip_prev) { TtsForegroundService.command(app, TtsForegroundService.ACTION_PREVIOUS) }
                button("TTS Next", Btn.TEXT, R.drawable.wna_skip_next) { TtsForegroundService.command(app, TtsForegroundService.ACTION_NEXT) }
            }
        }
        val content = ReaderContentRenderer.contentOrUndownloadedMessage(storage.readChapter(chapter) ?: chapter.content)
        val formattedText = TextCleanup.htmlToFormattedText(content)
        // R3: "Details" removed — the app-bar back arrow already returns to Details.
        // R4: reader chrome — font-size A-/A+ and a dark-reader toggle, applied live to the WebView.
        val display = storage.getDisplayPreferences()
        val reader = WebView(context).apply {
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
        flow {
            button("A−", Btn.TEXT) {
                display.readerFontScale = (display.readerFontScale - 0.1f).coerceIn(
                    PreferenceNormalization.READER_FONT_SCALE_MIN,
                    PreferenceNormalization.READER_FONT_SCALE_MAX,
                )
                storage.saveDisplayPreferences(display)
                renderReader()
            }
            button("A+", Btn.TEXT) {
                display.readerFontScale = (display.readerFontScale + 0.1f).coerceIn(
                    PreferenceNormalization.READER_FONT_SCALE_MIN,
                    PreferenceNormalization.READER_FONT_SCALE_MAX,
                )
                storage.saveDisplayPreferences(display)
                renderReader()
            }
            button(if (display.readerDark) "Light" else "Dark", Btn.TEXT) {
                display.readerDark = !display.readerDark
                storage.saveDisplayPreferences(display)
                renderReader()
            }
            button("Copy", Btn.TEXT, R.drawable.wna_copy) {
                copyToClipboard("Chapter text", formattedText)
                toast("Chapter copied")
            }
            button("Mark Read", Btn.TEXT, R.drawable.wna_check) {
                val latest = storage.getStory(readerStory.id) ?: readerStory
                storage.addOrUpdateStory(StoryBookmarkPlanning.withBookmark(latest, chapter.id, toggleExisting = false))
                toast("Marked as read")
            }
        }
        renderReader()
        addView(reader, LinearLayout.LayoutParams(-1, 0, 1f))
    }
}
