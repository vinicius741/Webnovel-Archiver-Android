package com.vinicius741.webnovelarchiver.feature.reader

import android.annotation.SuppressLint
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.vinicius741.webnovelarchiver.data.diagnostics.ActivityCategory
import com.vinicius741.webnovelarchiver.data.diagnostics.ActivityLog
import com.vinicius741.webnovelarchiver.feature.narration.playIfGenerated
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
    private var followSettleUntil: Long = 0L

    @Volatile
    private var listener: Job? = null
    private var healPending = false
    private var stoppingFling = false
    private var followToken = 0
    private var pendingFollowScroll: Runnable? = null
    private var pendingFollowVerify: Runnable? = null
    private var missingChapterLogged: String? = null

    init {
        page.addJavascriptInterface(Bridge(), ReaderReadAlongScript.BRIDGE)
        watchDrags()
        page.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) = startListening()

                override fun onViewDetachedFromWindow(view: View) {
                    fingerDownAt = null
                    cancelPendingFollow()
                    stopListening()
                }
            },
        )
    }

    /**
     * The pill: return to the spoken sentence.
     *
     * Stopping a WebView fling sends synthetic touch events through Chromium's input pipeline,
     * which processes them asynchronously on the compositor thread. Starting a programmatic scroll
     * in the same tick races with that touch and with trailing fling frames, which can cancel the
     * smooth scroll and leave the page where it was. Stop the fling first (hidden from drag
     * tracking), flip to following immediately so the pill hides, let the stop-fling events drain,
     * then scroll and verify that the sentence landed on screen.
     */
    fun follow() {
        cancelPendingFollow()
        fingerDownAt = null
        stoppingFling = true
        try {
            page.stopFling()
        } finally {
            stoppingFling = false
        }
        val token = ++followToken
        followSettleUntil = SystemClock.uptimeMillis() + FOLLOW_SCROLL_DELAY_MS
        apply(ReadAlongPlanning.afterFollow(state), allowScroll = false)
        scheduleFollowScroll(token)
    }

    private fun scheduleFollowScroll(token: Int) {
        val scroll =
            Runnable {
                pendingFollowScroll = null
                followSettleUntil = 0L
                if (canRunFollow(token)) {
                    page.evaluateJavascript(ReaderReadAlongScript.renderCall(state.sentence, ReadAlongScroll.SMOOTH), null)
                }
            }
        val verify =
            Runnable {
                pendingFollowVerify = null
                if (canRunFollow(token)) {
                    page.evaluateJavascript(ReaderReadAlongScript.renderCall(state.sentence, ReadAlongScroll.IF_OFF_SCREEN), null)
                }
            }
        pendingFollowScroll = scroll
        pendingFollowVerify = verify
        page.postDelayed(scroll, FOLLOW_SCROLL_DELAY_MS)
        page.postDelayed(verify, FOLLOW_VERIFY_DELAY_MS)
    }

    private fun canRunFollow(token: Int): Boolean {
        if (token != followToken || !page.isAttachedToWindow) return false
        return state.following && !fingerIsDown() && state.sentence >= 0
    }

    private fun cancelPendingFollow() {
        followToken++
        followSettleUntil = 0L
        pendingFollowScroll?.let(page::removeCallbacks)
        pendingFollowVerify?.let(page::removeCallbacks)
        pendingFollowScroll = null
        pendingFollowVerify = null
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
        cancelPendingFollow()
        val stopped = listener ?: return
        stopped.cancel()
        listener = null
        log("read_along_stopped")
    }

    private fun onPlayback(update: TtsPlaybackUpdate) {
        if (openPlayingChapter(update.snapshot)) return
        apply(ReadAlongPlanning.afterPlayback(state, update, storyId, chapterId))
    }

    /** Playback left this chapter for another chapter of this story while this page is on screen: open it. */
    private fun openPlayingChapter(snapshot: TtsPlaybackSnapshot?): Boolean {
        if (!state.showsPlayer || snapshot == null || snapshot.storyId != storyId || snapshot.chapterId == chapterId) return false
        // Only while the user is looking at this page; a skip from Now Playing must not pull it here.
        val route = host.navigator.current
        if (route !is AppRoute.Reader || route.storyId != storyId || route.chapterId != chapterId) return false
        val chapterIds =
            host.repository
                .story(storyId)
                ?.chapters
                ?.map { it.id }
                .orEmpty()
        val target = ReadAlongPlanning.chapterToOpen(state, storyId, chapterId, snapshot, chapterIds)
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
        val scroll = if (!allowScroll || scrollDeferred()) ReadAlongScroll.NONE else ReadAlongPlanning.scroll(before, next)
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

    /** True while a finger is down or while a follow tap's stop-fling touch is still draining. */
    private fun scrollDeferred(): Boolean = fingerIsDown() || SystemClock.uptimeMillis() < followSettleUntil

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
            if (stoppingFling) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    fingerDownAt = SystemClock.uptimeMillis()
                    downY = event.y
                    dragged = false
                }
                MotionEvent.ACTION_MOVE ->
                    if (!dragged && abs(event.y - downY) > slop) {
                        dragged = true
                        cancelPendingFollow()
                        apply(ReadAlongPlanning.afterDrag(state))
                    }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> fingerDownAt = null
            }
            false
        }
        page.setOnGenericMotionListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
                cancelPendingFollow()
                apply(ReadAlongPlanning.afterDrag(state))
            }
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
            return ReadAlongPlanning.pageState(latest, scrollDeferred())
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
                if (story.chapters.none { it.id == chapterId }) return@post
                // When this chapter is the active TTS session, advance the session (keeping a pause)
                // so read-along follows to the next chapter; otherwise navigate without touching TTS.
                val playing = host.ttsEngine.playbackState.value.snapshot
                if (playing?.storyId == storyId && playing.chapterId == chapterId) {
                    TtsForegroundService.command(host.app, TtsForegroundService.ACTION_NEXT_CHAPTER)
                } else {
                    readableChapter(story.chapters, chapterId, 1)?.let { host.showReader(storyId, it) }
                }
            }
        }
    }

    private companion object {
        const val HEAL_DELAY_MS = 500L
        const val FINGER_STALE_MS = 10_000L
        const val FOLLOW_SCROLL_DELAY_MS = 50L
        const val FOLLOW_VERIFY_DELAY_MS = 350L
    }
}

/**
 * Stops a running fling on this WebView without looking like a tap or a drag to the page.
 *
 * `flingScroll(0, 0)` aborts Android's OverScroller; the touchscreen `ACTION_DOWN` + `ACTION_CANCEL`
 * pair tells Chromium's compositor FlingController to cancel a touch fling.
 */
private fun WebView.stopFling() {
    flingScroll(0, 0)
    val now = SystemClock.uptimeMillis()
    val properties =
        arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_FINGER
            },
        )
    val coords =
        arrayOf(
            MotionEvent.PointerCoords().apply {
                x = width / 2f
                y = height / 2f
                pressure = 1f
                size = 1f
            },
        )
    for (action in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL)) {
        val event =
            MotionEvent.obtain(
                now,
                now,
                action,
                1,
                properties,
                coords,
                0,
                0,
                1f,
                1f,
                0,
                0,
                InputDevice.SOURCE_TOUCHSCREEN,
                0,
            )
        dispatchTouchEvent(event)
        event.recycle()
    }
}
