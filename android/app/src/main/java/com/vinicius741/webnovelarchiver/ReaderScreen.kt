package com.vinicius741.webnovelarchiver

import android.webkit.WebView
import android.widget.LinearLayout
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
    screen(title = chapter.title, subtitle = "${currentIndex + 1} / ${readerStory.chapters.size}", onBack = { showDetails(readerStory.id) }) {
        flow {
            button("Prev", Btn.TEXT, R.drawable.wna_skip_prev) { navigateChapter(readerStory, chapter, -1) }
            button("TTS", Btn.TONAL, R.drawable.wna_speaker) { TtsForegroundService.start(app, readerStory.id, chapter.id) }
            button("Pause", Btn.TEXT, R.drawable.wna_pause) { TtsForegroundService.command(app, TtsForegroundService.ACTION_PAUSE) }
            button("Stop", Btn.TEXT, R.drawable.wna_stop) { TtsForegroundService.command(app, TtsForegroundService.ACTION_STOP) }
            button("TTS Prev", Btn.TEXT, R.drawable.wna_skip_prev) { TtsForegroundService.command(app, TtsForegroundService.ACTION_PREVIOUS) }
            button("TTS Next", Btn.TEXT, R.drawable.wna_skip_next) { TtsForegroundService.command(app, TtsForegroundService.ACTION_NEXT) }
            button("Next", Btn.TEXT, R.drawable.wna_skip_next) { navigateChapter(readerStory, chapter, 1) }
        }
        val content = ReaderContentRenderer.contentOrUndownloadedMessage(storage.readChapter(chapter) ?: chapter.content)
        val formattedText = TextCleanup.htmlToFormattedText(content)
        flow {
            button("Copy", Btn.TEXT, R.drawable.wna_copy) {
                copyToClipboard("Chapter text", formattedText)
                toast("Chapter copied")
            }
            button("Mark Read", Btn.TEXT, R.drawable.wna_check) {
                val latest = storage.getStory(readerStory.id) ?: readerStory
                storage.addOrUpdateStory(StoryBookmarkPlanning.withBookmark(latest, chapter.id, toggleExisting = false))
                toast("Marked as read")
            }
            button("Details", Btn.TEXT, R.drawable.wna_list) { showDetails(readerStory.id) }
        }
        val reader = WebView(context).apply {
            settings.javaScriptEnabled = false
            settings.domStorageEnabled = false
            loadDataWithBaseURL(null, ReaderContentRenderer.document(chapter.title, content), "text/html", "utf-8", null)
        }
        addView(reader, LinearLayout.LayoutParams(-1, 0, 1f))
    }
}
