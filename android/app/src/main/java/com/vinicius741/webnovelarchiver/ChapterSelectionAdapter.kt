package com.vinicius741.webnovelarchiver

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vinicius741.webnovelarchiver.core.Chapter
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.ripple
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.sanitizeTitle
import com.vinicius741.webnovelarchiver.ui.size
import com.vinicius741.webnovelarchiver.ui.strokeBg

internal data class SelectableChapter(
    val originalIndex: Int,
    val chapter: Chapter,
)

internal class ChapterSelectionAdapter(
    private val items: List<SelectableChapter>,
    private val selectedIds: Set<String>,
    private val onTap: (Int) -> Unit,
) : RecyclerView.Adapter<ChapterSelectionAdapter.Holder>() {
    private var rangeAnchor: Int? = null

    internal class Holder(
        val row: LinearLayout,
        val checkbox: CheckBox,
        val title: TextView,
    ) : RecyclerView.ViewHolder(row)

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): Holder {
        val context = parent.context
        val checkbox =
            CheckBox(context).apply {
                buttonTintList =
                    ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                        intArrayOf(ThemeManager.colors.primary, ThemeManager.colors.outline),
                    )
            }
        val title =
            TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, Type.TITLE_SMALL.size())
                typeface = Typeface.create(typeface, Typeface.BOLD)
                setTextColor(ThemeManager.colors.onSurface)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
            }
        val row =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = context.dp(56)
                setPadding(context.dp(Space.MD), context.dp(Space.XS), context.dp(Space.LG), context.dp(Space.XS))
                isClickable = true
                isFocusable = true
                layoutParams =
                    RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = context.dp(Space.XS)
                    }
                addView(checkbox)
                addView(
                    title,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = context.dp(Space.MD)
                    },
                )
            }
        return Holder(row, checkbox, title)
    }

    override fun onBindViewHolder(
        holder: Holder,
        position: Int,
    ) {
        val item = items[position]
        val selected = item.chapter.id in selectedIds
        val anchored = position == rangeAnchor
        val colors = ThemeManager.colors
        val radius =
            holder.row.context
                .dp(ThemeManager.shapes.cardRadius)
                .toFloat()
        val background =
            when {
                anchored -> strokeBg(colors.elevation1, radius, colors.primary, holder.row.context.dp(2))
                selected -> roundedBg(colors.secondaryContainer, radius)
                else -> roundedBg(colors.elevation1, radius)
            }

        holder.checkbox.setOnCheckedChangeListener(null)
        holder.checkbox.isChecked = selected
        holder.checkbox.setOnCheckedChangeListener { _, _ ->
            holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let(onTap)
        }
        holder.title.text = "${item.originalIndex + 1}. ${sanitizeTitle(item.chapter.title)}"
        holder.row.background = ripple(background, radius, colors.onSurface)
        holder.row.contentDescription =
            "Chapter ${item.originalIndex + 1}, ${if (selected) "selected" else "not selected"}"
        holder.row.setOnClickListener {
            holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let(onTap)
        }
    }

    override fun getItemCount(): Int = items.size

    fun refreshSelection(
        previous: Set<String>,
        current: Set<String>,
    ) {
        val changedPositions = items.indices.filter { (items[it].chapter.id in previous) != (items[it].chapter.id in current) }
        if (changedPositions.isNotEmpty()) {
            notifyItemRangeChanged(changedPositions.first(), changedPositions.last() - changedPositions.first() + 1)
        }
    }

    fun setRangeAnchor(
        next: Int?,
        previous: Int?,
    ) {
        rangeAnchor = next
        previous?.let(::notifyItemChanged)
        if (next != previous) next?.let(::notifyItemChanged)
    }
}

/** Long-presses a row into a paint gesture. Moving toward either edge auto-scrolls the list. */
internal class ChapterDragSelectionTouchListener(
    private val list: RecyclerView,
    private val currentSelection: () -> Set<String>,
    private val isSelected: (Int) -> Boolean,
    private val onDragStarted: (selecting: Boolean) -> Unit,
    private val onRangeChanged: (baseSelection: Set<String>, start: Int, end: Int, selecting: Boolean) -> Unit,
    private val onDragFinished: () -> Unit,
) : RecyclerView.SimpleOnItemTouchListener() {
    private val handler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(list.context).scaledTouchSlop
    private val edgeSize = list.context.dp(72)
    private val maxScrollStep = list.context.dp(18)
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downPosition = RecyclerView.NO_POSITION
    private var anchorPosition = RecyclerView.NO_POSITION
    private var currentPosition = RecyclerView.NO_POSITION
    private var selecting = true
    private var dragging = false
    private var scrollStep = 0
    private var baseSelection: Set<String> = emptySet()

    private val beginDrag =
        Runnable {
            if (downPosition == RecyclerView.NO_POSITION || !list.isAttachedToWindow) return@Runnable
            dragging = true
            anchorPosition = downPosition
            currentPosition = downPosition
            selecting = !isSelected(downPosition)
            baseSelection = currentSelection()
            list.parent?.requestDisallowInterceptTouchEvent(true)
            list.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onDragStarted(selecting)
            onRangeChanged(baseSelection, anchorPosition, currentPosition, selecting)
            updateAutoScroll()
        }

    private val autoScroll =
        object : Runnable {
            override fun run() {
                if (!dragging || scrollStep == 0 || !list.isAttachedToWindow) return
                list.scrollBy(0, scrollStep)
                updateDragPosition(positionNear(lastX, lastY))
                list.postOnAnimation(this)
            }
        }

    override fun onInterceptTouchEvent(
        recyclerView: RecyclerView,
        event: MotionEvent,
    ): Boolean = handle(event)

    override fun onTouchEvent(
        recyclerView: RecyclerView,
        event: MotionEvent,
    ) {
        handle(event)
    }

    private fun handle(event: MotionEvent): Boolean {
        lastX = event.x
        lastY = event.y
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downPosition = positionUnder(event.x, event.y)
                handler.removeCallbacks(beginDrag)
                if (downPosition != RecyclerView.NO_POSITION) {
                    handler.postDelayed(beginDrag, ViewConfiguration.getLongPressTimeout().toLong())
                }
                false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) {
                    val moved = kotlin.math.abs(event.x - downX) > touchSlop || kotlin.math.abs(event.y - downY) > touchSlop
                    if (moved) handler.removeCallbacks(beginDrag)
                    false
                } else {
                    updateDragPosition(positionNear(event.x, event.y))
                    updateAutoScroll()
                    true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val consumed = dragging
                finishDrag()
                consumed
            }
            else -> dragging
        }
    }

    private fun updateDragPosition(position: Int) {
        if (position == RecyclerView.NO_POSITION || position == currentPosition) return
        currentPosition = position
        onRangeChanged(baseSelection, anchorPosition, currentPosition, selecting)
    }

    private fun updateAutoScroll() {
        list.removeCallbacks(autoScroll)
        scrollStep =
            when {
                lastY < edgeSize -> -scrollSpeed(edgeSize - lastY)
                lastY > list.height - edgeSize -> scrollSpeed(lastY - (list.height - edgeSize))
                else -> 0
            }
        if (scrollStep != 0) list.postOnAnimation(autoScroll)
    }

    private fun scrollSpeed(distanceIntoEdge: Float): Int {
        val fraction = (distanceIntoEdge / edgeSize).coerceIn(0.25f, 1f)
        return (maxScrollStep * fraction).toInt()
    }

    private fun positionNear(
        x: Float,
        y: Float,
    ): Int {
        positionUnder(x, y.coerceIn(1f, (list.height - 1).coerceAtLeast(1).toFloat())).let {
            if (it != RecyclerView.NO_POSITION) return it
        }
        val manager = list.layoutManager as? LinearLayoutManager ?: return RecyclerView.NO_POSITION
        return if (y < list.height / 2f) manager.findFirstVisibleItemPosition() else manager.findLastVisibleItemPosition()
    }

    private fun positionUnder(
        x: Float,
        y: Float,
    ): Int = list.findChildViewUnder(x, y)?.let(list::getChildAdapterPosition) ?: RecyclerView.NO_POSITION

    private fun finishDrag() {
        handler.removeCallbacks(beginDrag)
        list.removeCallbacks(autoScroll)
        if (dragging) onDragFinished()
        dragging = false
        scrollStep = 0
        downPosition = RecyclerView.NO_POSITION
        anchorPosition = RecyclerView.NO_POSITION
        currentPosition = RecyclerView.NO_POSITION
        baseSelection = emptySet()
        list.parent?.requestDisallowInterceptTouchEvent(false)
    }
}
