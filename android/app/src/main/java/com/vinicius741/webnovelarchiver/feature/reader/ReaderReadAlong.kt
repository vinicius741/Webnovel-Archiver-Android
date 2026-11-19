package com.vinicius741.webnovelarchiver.feature.reader

import android.annotation.SuppressLint
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.vinicius741.webnovelarchiver.data.diagnostics.ActivityCategory
import com.vinicius741.webnovelarchiver.data.diagnostics.ActivityLog
import com.vinicius741.webnovelarchiver.feature.narration.playIfGenerated
import com.vinicius741.webnovelarchiver.feature.story.navigateChapter
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.tts.TtsForegroundService
import com.vinicius741.webnovelarchiver.tts.TtsPlaybackSnapshot
import com.vinicius741.webnovelarchiver.tts.TtsPlaybackUpdate
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Keeps one reader page in step with read-aloud playback: highlights and follows the spoken
 * sentence, tells the screen when to show the docked player and the "Back to current sentence"
 * pill ([onChange]), and opens the chapter playback moves to.
 *
 * It lives exactly as long as its page. It listens to the engine while the page is attached to
 * the window and stops when the page is removed; nothing outside cancels it. Every decision is in
 * [ReadAlongPlanning], and this is the only copy of the state. The page also asks for the engine's
 * state on its own (see [ReaderReadAlongScript]); when that differs from what was last sent, the
 * listener is checked and restarted if it stopped, and the repair is logged.
 */
internal class ReaderReadAlong(
    private val host: ScreenHost,
    private val page: WebView,
    private val storyId: String,
    private val chapterId: String,
    initial: TtsPlaybackSnapshot?,
    private val onChange: (ReadAlongState) -> Unit,
) {
    /** Written on the main thread; the page's bridge thread reads it. */
    @Volatile
    var state: ReadAlongState = ReadAlongState(initial)
        private set

    @Volatile
    private var fingerDownAt: Long? = null

    @Volatile
    private var listener: Job? = null
    private var healPending = false
    private var missingChapterLogged: String? = null

    init {
        page.addJavascriptInterface(Bridge(), ReaderReadAlongScript.BRIDGE)
        watchDrags()
        page.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) = startListening()

                override fun onViewDetachedFromWindow(view: View) {
                    fingerDownAt = null
                    stopListening()
                }
            },
        )
    }

    /**
     * The pill: return to the spoken sentence. The pill is usually tapped right after the page was
     * flung away, and a fling still running keeps moving the page after the scroll back lands (the
     * page's scroll does not stop it), so it is stopped first, the way a finger on the page would.
     */
    fun follow() {
        page.stopFling()
        apply(ReadAlongPlanning.afterFollow(state))
    }

    private fun startListening() {
        listener?.cancel()
        log("read_along_listening")
        // Dispatched, not immediate: attaching happens inside the screen build, and the first
        // update may open another chapter, which must not re-enter that build.
        listener =
            host.scope.launch(Dispatchers.Main) {
                host.ttsEngine.playbackState.collect(::onPlayback)
            }
    }

    private fun stopListening() {
        val stopped = listener ?: return
        stopped.cancel()
        listener = null
        log("read_along_stopped")
    }

    private fun onPlayback(update: TtsPlaybackUpdate) {
        if (openPlayingChapter(update.snapshot)) return
        apply(ReadAlongPlanning.afterPlayback(state, update, storyId, chapterId))
    }

    /** Playback moved to another chapter of this story while this page is on screen: open it. */
    private fun openPlayingChapter(snapshot: TtsPlaybackSnapshot?): Boolean {
        if (snapshot == null || snapshot.storyId != storyId || snapshot.chapterId == chapterId) return false
        // Only while the user is looking at this page; a skip from Now Playing must not pull it here.
        val route = host.navigator.current
        if (route !is AppRoute.Reader || route.storyId != storyId || route.chapterId != chapterId) return false
        val chapterIds =
            host.repository
                .story(storyId)
                ?.chapters
                ?.map { it.id }
                .orEmpty()
        val target = ReadAlongPlanning.chapterToOpen(storyId, chapterId, snapshot, chapterIds)
        if (target == null) {
            if (missingChapterLogged != snapshot.chapterId) {
                missingChapterLogged = snapshot.chapterId
                log("read_along_chapter_missing", "playingChapterId" to snapshot.chapterId)
            }
            return false
        }
        stopListening()
        host.showReader(storyId, target)
        return true
    }

    /** [allowScroll] false updates the page without moving it (a double-tap: the new sentence scrolls once it plays). */
    private fun apply(
        next: ReadAlongState,
        allowScroll: Boolean = true,
    ) {
        val before = state
        if (next == before) return
        state = next
        if (before.following != next.following) log("read_along_following", "following" to next.following)
        val scroll = if (!allowScroll || fingerIsDown()) ReadAlongScroll.NONE else ReadAlongPlanning.scroll(before, next)
        if (before.sentence != next.sentence || scroll != ReadAlongScroll.NONE) {
            page.evaluateJavascript(ReaderReadAlongScript.renderCall(next.sentence, scroll), null)
        }
        onChange(next)
    }

    /**
     * The page found the engine somewhere this page was not told about, or the listener stopped.
     * Wait briefly, since the update may simply be on its way, then restart the listener if it
     * stopped (it replays the latest state) or catch up from the engine.
     */
    private fun scheduleHeal() {
        if (healPending) return
        healPending = true
        page.postDelayed({
            healPending = false
            if (!page.isAttachedToWindow) return@postDelayed
            val latest = host.ttsEngine.playbackState.value
            val listening = listener?.isActive == true
            val caughtUp = ReadAlongPlanning.afterPlayback(state, latest, storyId, chapterId) == state
            if (listening && caughtUp) return@postDelayed
            log("read_along_healed", "listenerWasActive" to listening, "sentence" to latest.snapshot?.chunkIndex)
            if (listening) onPlayback(latest) else startListening()
        }, HEAL_DELAY_MS)
    }

    /** A finger is on the page. A down whose up never arrived stops counting after a while. */
    private fun fingerIsDown(): Boolean = fingerDownAt?.let { SystemClock.uptimeMillis() - it < FINGER_STALE_MS } == true

    /**
     * Dragging the page past Android's touch slop (the distance at which the page itself starts
     * scrolling), or scrolling it with a wheel, stops following. While a finger is down the page is
     * never scrolled for it.
     */
    @SuppressLint("ClickableViewAccessibility") // Observes only; returns false so the page handles the touch.
    private fun watchDrags() {
        val slop = ViewConfiguration.get(page.context).scaledTouchSlop
        var downY = 0f
        var dragged = false
        page.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    fingerDownAt = SystemClock.uptimeMillis()
                    downY = event.y
                    dragged = false
                }
                MotionEvent.ACTION_MOVE ->
                    if (!dragged && abs(event.y - downY) > slop) {
                        dragged = true
                        apply(ReadAlongPlanning.afterDrag(state))
                    }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> fingerDownAt = null
            }
            false
        }
        page.setOnGenericMotionListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_SCROLL) apply(ReadAlongPlanning.afterDrag(state))
            false
        }
    }

    private fun log(
        type: String,
        vararg fields: Pair<String, Any?>,
    ) = ActivityLog.record(ActivityCategory.READER, type, "storyId" to storyId, "chapterId" to chapterId, *fields)

    /** Called by the page script on the WebView's bridge thread. */
    private inner class Bridge {
        @JavascriptInterface
        fun readAlongState(): String {
            val current = state
            val latest = ReadAlongPlanning.afterPlayback(current, host.ttsEngine.playbackState.value, storyId, chapterId)
            if (latest.sentence != current.sentence || listener?.isActive != true) page.post { scheduleHeal() }
            return ReadAlongPlanning.pageState(latest, fingerIsDown())
        }

        @JavascriptInterface
        fun onSentenceDoubleTap(index: Int) {
            val sentence = index.coerceAtLeast(0)
            page.post {
                apply(ReadAlongPlanning.afterFollow(state), allowScroll = false)
                val story = host.repository.story(storyId) ?: return@post
                if (story.chapters.none { it.id == chapterId }) return@post
                // Audio of older text has other sentences than the page: a tap there cannot seek.
                val playing = host.ttsEngine.playbackState.value.snapshot
                if (playing?.olderText == true && playing.storyId == storyId && playing.chapterId == chapterId) {
                    host.toast("This audio was made from older text. Regenerate it to start from a sentence.")
                    return@post
                }
                host.playIfGenerated(storyId, chapterId) {
                    TtsForegroundService.startFromChunk(host.app, storyId, chapterId, sentence)
                }
            }
        }

        @JavascriptInterface
        fun onNextChapter() {
            page.post {
                val route = host.navigator.current
                if (route !is AppRoute.Reader || route.storyId != storyId || route.chapterId != chapterId) return@post
                val story = host.repository.story(storyId) ?: return@post
                val chapter = story.chapters.firstOrNull { it.id == chapterId } ?: return@post
                // With this story's session loaded the reader follows the spoken chapter, so opening
                // the next one directly would bounce back; move the session instead (it keeps a pause).
                if (host.ttsEngine.playbackState.value.snapshot
                        ?.storyId == storyId
                ) {
                    TtsForegroundService.command(host.app, TtsForegroundService.ACTION_NEXT_CHAPTER)
                } else {
                    host.navigateChapter(story, chapter, 1)
                }
            }
        }
    }

    private companion object {
        const val HEAL_DELAY_MS = 500L
        const val FINGER_STALE_MS = 10_000L
    }
}

/** A touch that lands and is cancelled: the page stops a running fling without seeing a tap or a drag. */
private fun WebView.stopFling() {
    val now = SystemClock.uptimeMillis()
    for (action in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL)) {
        val event = MotionEvent.obtain(now, now, action, width / 2f, height / 2f, 0)
        dispatchTouchEvent(event)
        event.recycle()
    }
}
