package com.vinicius741.webnovelarchiver.feature.settings

import android.annotation.SuppressLint
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.ui.dp

/**
 * Drag-to-reorder for the rows of a vertical [list] that holds nothing but those rows. Pressing a
 * row's handle lifts the row; it follows the finger while the other rows slide out of its way, and
 * on release the row is put in its new place and [onMove] gets its old and new index.
 */
internal class DragReorderList(
    private val list: LinearLayout,
    private val onMove: (from: Int, to: Int) -> Unit,
) {
    private var dragged: View? = null
    private var from = 0
    private var slot = 0
    private var startRawY = 0f

    @SuppressLint("ClickableViewAccessibility")
    fun attach(
        row: View,
        handle: View,
    ) {
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> start(row, event.rawY)
                MotionEvent.ACTION_MOVE -> follow(row, event.rawY)
                MotionEvent.ACTION_UP -> drop(row, commit = true)
                MotionEvent.ACTION_CANCEL -> drop(row, commit = false)
                else -> false
            }
        }
    }

    private fun start(
        row: View,
        rawY: Float,
    ): Boolean {
        if (dragged != null || list.childCount < 2) return false
        dragged = row
        from = list.indexOfChild(row)
        slot = from
        startRawY = rawY
        // Keeps the scroll view from taking the gesture as a scroll.
        list.parent?.requestDisallowInterceptTouchEvent(true)
        row.animate().cancel()
        row
            .animate()
            .scaleX(LIFT_SCALE)
            .scaleY(LIFT_SCALE)
            .translationZ(list.context.dp(LIFT_DP).toFloat())
            .setDuration(LIFT_MS)
            .start()
        row.alpha = LIFT_ALPHA
        row.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        return true
    }

    private fun follow(
        row: View,
        rawY: Float,
    ): Boolean {
        if (dragged !== row) return false
        val first = list.getChildAt(0)
        val last = list.getChildAt(list.childCount - 1)
        val dy = (rawY - startRawY).coerceIn((first.top - row.top).toFloat(), (last.bottom - row.bottom).toFloat())
        row.translationY = dy
        val center = row.top + row.height / 2f + dy
        var next = 0
        // A row is passed once the dragged center reaches its center; at either end the drag is
        // clamped to exactly that center, so the first and last slots must count it as passed.
        forEachOther(row) { index, child ->
            val childCenter = child.top + child.height / 2f
            if (if (index < from) childCenter < center else childCenter <= center) next++
        }
        if (next != slot) {
            slot = next
            row.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            val step = (row.height + row.bottomMargin()).toFloat()
            forEachOther(row) { index, child ->
                val shift =
                    when {
                        index in slot until from -> step
                        index in (from + 1)..slot -> -step
                        else -> 0f
                    }
                if (child.translationY != shift) {
                    child
                        .animate()
                        .translationY(shift)
                        .setDuration(SHIFT_MS)
                        .start()
                }
            }
        }
        return true
    }

    private fun drop(
        row: View,
        commit: Boolean,
    ): Boolean {
        if (dragged !== row) return false
        dragged = null
        list.parent?.requestDisallowInterceptTouchEvent(false)
        row.alpha = 1f
        row
            .animate()
            .scaleX(1f)
            .scaleY(1f)
            .translationZ(0f)
            .setDuration(LIFT_MS)
            .start()
        val to = slot
        if (commit && to != from) {
            // The rows are put in their new order now, so nothing snaps back while the save runs.
            for (i in 0 until list.childCount) {
                list.getChildAt(i).apply {
                    animate().translationY(0f).setDuration(0).start()
                    translationY = 0f
                }
            }
            list.removeView(row)
            list.addView(row, to)
            onMove(from, to)
        } else {
            forEachOther(row) { _, child ->
                child
                    .animate()
                    .translationY(0f)
                    .setDuration(SHIFT_MS)
                    .start()
            }
            row
                .animate()
                .translationY(0f)
                .setDuration(SHIFT_MS)
                .start()
        }
        return true
    }

    private inline fun forEachOther(
        row: View,
        action: (index: Int, child: View) -> Unit,
    ) {
        for (i in 0 until list.childCount) {
            val child = list.getChildAt(i)
            if (child !== row) action(i, child)
        }
    }

    private fun View.bottomMargin(): Int = (layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0

    private companion object {
        const val LIFT_DP = 8
        const val LIFT_SCALE = 1.02f
        const val LIFT_ALPHA = 0.95f
        const val LIFT_MS = 120L
        const val SHIFT_MS = 150L
    }
}
