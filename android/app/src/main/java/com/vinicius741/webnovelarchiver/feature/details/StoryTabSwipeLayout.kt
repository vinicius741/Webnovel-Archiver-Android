package com.vinicius741.webnovelarchiver.feature.details

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.AbsSeekBar
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Wraps a story tab's body so a horizontal drag moves to the neighbouring tab, like the Library's
 * pager. The body follows the finger; past a quarter of the width (or on a fling) it slides away
 * and [onCommit] opens the next (+1) or previous (-1) tab. A vertical drag, or one that starts on
 * something that scrolls sideways itself (a chip row, a slider), is left to the child.
 */
@SuppressLint("ViewConstructor")
internal class StoryTabSwipeLayout(
    context: Context,
    private val canSwipe: (step: Int) -> Boolean,
    private val onCommit: (step: Int) -> Unit,
) : FrameLayout(context) {
    private val config = ViewConfiguration.get(context)
    private val touchSlop = config.scaledTouchSlop
    private val minFling = config.scaledMinimumFlingVelocity * FLING_FACTOR
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var ignoring = false
    private var step = 0
    private var committed = false
    private var velocity: VelocityTracker? = null

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        track(ev)
        return dragging
    }

    @SuppressLint("ClickableViewAccessibility") // Swiping is a shortcut; the tab strip stays the accessible path.
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        track(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (dragging) follow(ev.x - downX)
            MotionEvent.ACTION_UP -> if (dragging) release(ev.x - downX) else reset()
            MotionEvent.ACTION_CANCEL -> if (dragging) settleBack() else reset()
        }
        // Claim the gesture from its start so a drag over empty space still reaches us.
        return !committed
    }

    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        if (disallowIntercept && !dragging) ignoring = true
        super.requestDisallowInterceptTouchEvent(disallowIntercept)
    }

    private fun track(ev: MotionEvent) {
        if (committed) return
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                dragging = false
                ignoring = false
                velocity?.recycle()
                velocity = VelocityTracker.obtain()
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (!dragging) ignoring = true
            MotionEvent.ACTION_MOVE -> if (!dragging && !ignoring) detect(ev)
        }
        velocity?.addMovement(ev)
    }

    private fun detect(ev: MotionEvent) {
        val dx = ev.x - downX
        val dy = ev.y - downY
        if (abs(dy) > touchSlop && abs(dy) >= abs(dx)) {
            ignoring = true
            return
        }
        if (abs(dx) <= touchSlop || abs(dx) < abs(dy) * DIRECTION_RATIO) return
        val wanted = if (dx < 0) 1 else -1
        if (!canSwipe(wanted) || scrollsSideways(this, this, downX.toInt(), downY.toInt(), wanted)) {
            ignoring = true
            return
        }
        step = wanted
        dragging = true
        parent?.requestDisallowInterceptTouchEvent(true)
    }

    private val body: View? get() = getChildAt(0)

    private fun follow(dx: Float) {
        // Only toward the neighbour that exists; dragging back past the start rests at zero.
        val offset = if (step > 0) dx.coerceAtMost(0f) else dx.coerceAtLeast(0f)
        body?.translationX = offset
    }

    private fun release(dx: Float) {
        val tracker = velocity
        tracker?.computeCurrentVelocity(VELOCITY_UNITS)
        val vx = tracker?.xVelocity ?: 0f
        val far = abs(dx) > width * COMMIT_FRACTION && (dx < 0) == (step > 0)
        val flung = abs(vx) > minFling && (vx < 0) == (step > 0) && abs(dx) > touchSlop
        if (far || flung) commit() else settleBack()
    }

    private fun commit() {
        committed = true
        val target = step
        val view = body
        if (view == null) {
            onCommit(target)
            return
        }
        view
            .animate()
            .translationX(-target * width.toFloat())
            .alpha(0f)
            .setDuration(EXIT_MS)
            .withEndAction { onCommit(target) }
            .start()
        reset()
    }

    private fun settleBack() {
        body
            ?.animate()
            ?.translationX(0f)
            ?.setDuration(SETTLE_MS)
            ?.start()
        reset()
    }

    private fun reset() {
        dragging = false
        velocity?.recycle()
        velocity = null
    }

    private companion object {
        const val COMMIT_FRACTION = 0.25f
        const val DIRECTION_RATIO = 1.5f
        const val FLING_FACTOR = 8
        const val VELOCITY_UNITS = 1000
        const val EXIT_MS = 140L
        const val SETTLE_MS = 180L
    }
}

/** True when the view under ([x], [y]) can itself scroll toward [step] (content moving left for +1). */
private fun scrollsSideways(
    root: View,
    view: View,
    x: Int,
    y: Int,
    step: Int,
): Boolean {
    if (view !== root && (view.canScrollHorizontally(step) || view is AbsSeekBar)) return true
    if (view !is ViewGroup) return false
    for (i in view.childCount - 1 downTo 0) {
        val child = view.getChildAt(i)
        if (child.visibility != View.VISIBLE) continue
        val cx = x + view.scrollX - child.left - child.translationX.toInt()
        val cy = y + view.scrollY - child.top - child.translationY.toInt()
        if (cx >= 0 && cy >= 0 && cx < child.width && cy < child.height && scrollsSideways(root, child, cx, cy, step)) {
            return true
        }
    }
    return false
}
