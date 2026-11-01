package com.vinicius741.webnovelarchiver.feature.downloads

import android.annotation.SuppressLint
import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.download.QueueAction
import com.vinicius741.webnovelarchiver.download.QueueSection
import com.vinicius741.webnovelarchiver.download.SourceCheckCard
import com.vinicius741.webnovelarchiver.download.SourceCheckPhase
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.IconButtonStyle
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.iconButton
import com.vinicius741.webnovelarchiver.ui.makeBadge
import com.vinicius741.webnovelarchiver.ui.makeButton
import com.vinicius741.webnovelarchiver.ui.makeCard
import com.vinicius741.webnovelarchiver.ui.makeProgress
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.selectableRipple
import com.vinicius741.webnovelarchiver.ui.tintedIcon

/** What the Downloads list asks its screen to do; the screen owns every queue mutation. */
internal interface QueueListActions {
    /** [trouble] opens the fallbacks instead of the check itself. */
    fun onCheck(
        card: SourceCheckCard,
        trouble: Boolean,
    )

    fun onRetryFailed()

    fun onToggleFinished()

    fun onClearFinished()

    fun onToggleStory(storyId: String)

    fun onStoryPrimary(item: QueueStoryItem)

    fun onStoryMenu(item: QueueStoryItem)

    fun onChapterRetry(item: QueueChapterItem)

    fun onChapterMenu(item: QueueChapterItem)

    /** The up-next novels in their new order after a drag. */
    fun onReorder(upNextOrder: List<String>)
}

internal class QueueListAdapter(
    private val context: Context,
    private val actions: QueueListActions,
) : RecyclerView.Adapter<QueueListAdapter.Holder>() {
    private var items: MutableList<QueueItem> = mutableListOf()

    /** Updates that arrive mid-drag wait here so the dragged card isn't yanked back. */
    private var pendingItems: List<QueueItem>? = null
    private var dragging = false
    private var dragMoved = false

    val touchHelper = ItemTouchHelper(DragCallback())

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = items[position].key.hashCode().toLong()

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int =
        when (items[position]) {
            is QueueSummaryItem -> TYPE_SUMMARY
            is QueueCheckItem -> TYPE_CHECK
            is QueueFailedItem -> TYPE_FAILED
            is QueueHeaderItem -> TYPE_HEADER
            is QueueStoryItem -> TYPE_STORY
            is QueueChapterItem -> TYPE_CHAPTER
        }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): Holder =
        when (viewType) {
            TYPE_SUMMARY -> SummaryHolder(context)
            TYPE_CHECK -> CheckHolder(context, actions)
            TYPE_FAILED -> FailedHolder(context, actions)
            TYPE_HEADER -> HeaderHolder(context, actions)
            TYPE_STORY -> StoryHolder(context, actions, touchHelper)
            else -> ChapterHolder(context, actions)
        }

    override fun onBindViewHolder(
        holder: Holder,
        position: Int,
    ) = holder.bind(items[position])

    fun submit(next: List<QueueItem>) {
        if (dragging) {
            pendingItems = next
            return
        }
        val previous = items
        items = next.toMutableList()
        DiffUtil
            .calculateDiff(
                object : DiffUtil.Callback() {
                    override fun getOldListSize() = previous.size

                    override fun getNewListSize() = next.size

                    override fun areItemsTheSame(
                        old: Int,
                        new: Int,
                    ) = previous[old].key == next[new].key

                    override fun areContentsTheSame(
                        old: Int,
                        new: Int,
                    ) = previous[old] == next[new]
                },
            ).dispatchUpdatesTo(this)
    }

    private inner class DragCallback : ItemTouchHelper.Callback() {
        override fun getMovementFlags(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
        ): Int {
            val item = items.getOrNull(viewHolder.bindingAdapterPosition) as? QueueStoryItem
            return if (item?.draggable == true) makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) else 0
        }

        override fun canDropOver(
            recyclerView: RecyclerView,
            current: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder,
        ): Boolean = (items.getOrNull(target.bindingAdapterPosition) as? QueueStoryItem)?.draggable == true

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder,
        ): Boolean {
            val from = viewHolder.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from < 0 || to < 0) return false
            items.add(to, items.removeAt(from))
            notifyItemMoved(from, to)
            dragMoved = true
            return true
        }

        override fun onSwiped(
            viewHolder: RecyclerView.ViewHolder,
            direction: Int,
        ) = Unit

        override fun isLongPressDragEnabled(): Boolean = true

        override fun onSelectedChanged(
            viewHolder: RecyclerView.ViewHolder?,
            actionState: Int,
        ) {
            super.onSelectedChanged(viewHolder, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                dragging = true
                dragMoved = false
                viewHolder?.itemView?.alpha = 0.92f
            }
        }

        override fun clearView(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
        ) {
            super.clearView(recyclerView, viewHolder)
            viewHolder.itemView.alpha = 1f
            dragging = false
            val moved = dragMoved
            dragMoved = false
            val waiting = pendingItems
            pendingItems = null
            if (moved) {
                // The saved order arrives with the next queue publish; a stale pending update
                // would snap the card back for a frame first.
                actions.onReorder(items.filterIsInstance<QueueStoryItem>().filter { it.section == QueueSection.UP_NEXT }.map { it.storyId })
            } else if (waiting != null) {
                submit(waiting)
            }
        }
    }

    abstract class Holder(
        view: View,
    ) : RecyclerView.ViewHolder(view) {
        abstract fun bind(item: QueueItem)
    }

    private class SummaryHolder(
        context: Context,
    ) : Holder(makeText(context, "", Type.BODY_MEDIUM, ThemeManager.colors.onSurfaceVariant).withRowParams(bottom = Space.MD)) {
        override fun bind(item: QueueItem) {
            (itemView as TextView).text = (item as QueueSummaryItem).text
        }
    }

    private class CheckHolder(
        context: Context,
        actions: QueueListActions,
    ) : Holder(makeCard(context).withRowParams(bottom = Space.MD)) {
        private val card = itemView as LinearLayout
        private val badgeSlot = LinearLayout(context)
        private val title = makeText(context, "", Type.TITLE_MEDIUM, ThemeManager.colors.onSurface)
        private val body = makeText(context, "", Type.BODY_MEDIUM, ThemeManager.colors.onSurfaceVariant)
        private val buttons = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        private var bound: SourceCheckCard? = null
        private val primary = makeButton(context, "Verify now", Btn.FILLED) { bound?.let { actions.onCheck(it, trouble = false) } }
        private val trouble = makeButton(context, "Having trouble?", Btn.TEXT) { bound?.let { actions.onCheck(it, trouble = true) } }

        init {
            card.addView(badgeSlot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            card.addView(title, verticalGap(context, Space.SM))
            card.addView(body, verticalGap(context, Space.XS))
            buttons.addView(primary)
            buttons.addView(
                trouble,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = context.dp(Space.SM)
                },
            )
            card.addView(buttons, verticalGap(context, Space.MD))
        }

        override fun bind(item: QueueItem) {
            val check = item as QueueCheckItem
            val card = check.card
            bound = card
            val colors = ThemeManager.colors
            val chapters = plural(card.chapterCount, "chapter")
            val (badge, badgeBg, badgeFg) =
                when (card.phase) {
                    SourceCheckPhase.NEEDS_CHECK -> Triple("Verification needed", colors.errorContainer, colors.onErrorContainer)
                    SourceCheckPhase.STILL_BLOCKED -> Triple("Still blocked", colors.errorContainer, colors.onErrorContainer)
                    SourceCheckPhase.VERIFIED -> Triple("Verified", colors.primaryContainer, colors.onPrimaryContainer)
                }
            badgeSlot.removeAllViews()
            badgeSlot.addView(makeBadge(itemView.context, badge, badgeBg, badgeFg))
            when (card.phase) {
                SourceCheckPhase.NEEDS_CHECK -> {
                    title.text = "${check.siteName} needs a quick check"
                    body.text =
                        "${plural(card.storyCount, "novel")} · $chapters paused until you verify. Other sites keep going."
                    primary.text = "Verify now"
                }
                SourceCheckPhase.STILL_BLOCKED -> {
                    title.text = "${check.siteName} is still blocked"
                    body.text = "The check didn't finish, so $chapters stay paused. Try again, or see other options."
                    primary.text = "Try again"
                }
                SourceCheckPhase.VERIFIED -> {
                    title.text = "${check.siteName} verified"
                    body.text = if (card.chapterCount > 0) "Resuming $chapters." else "Downloads can continue."
                }
            }
            buttons.visibility = if (card.phase == SourceCheckPhase.VERIFIED) View.GONE else View.VISIBLE
        }
    }

    private class FailedHolder(
        context: Context,
        actions: QueueListActions,
    ) : Holder(makeCard(context).withRowParams(bottom = Space.MD)) {
        private val title = makeText(context, "", Type.TITLE_MEDIUM, ThemeManager.colors.onSurface)

        init {
            val card = itemView as LinearLayout
            card.addView(title)
            card.addView(
                makeText(
                    context,
                    "Retrying often works. Open a novel's chapters to see why each one failed.",
                    Type.BODY_MEDIUM,
                    ThemeManager.colors.onSurfaceVariant,
                ),
                verticalGap(context, Space.XS),
            )
            card.addView(
                makeButton(context, "Retry all", Btn.TONAL, R.drawable.wna_refresh) { actions.onRetryFailed() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = context.dp(Space.MD)
                },
            )
        }

        override fun bind(item: QueueItem) {
            val count = (item as QueueFailedItem).count
            title.text = if (count == 1) "1 chapter couldn't download" else "$count chapters couldn't download"
        }
    }

    private class HeaderHolder(
        context: Context,
        actions: QueueListActions,
    ) : Holder(
            LinearLayout(context)
                .apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = context.dp(40)
                }.withRowParams(top = Space.SM, bottom = Space.XS),
        ) {
        private val row = itemView as LinearLayout
        private val chevron =
            ImageView(context).apply {
                setImageDrawable(context.tintedIcon(R.drawable.wna_chevron_down, ThemeManager.colors.primary))
                layoutParams = LinearLayout.LayoutParams(context.dp(20), context.dp(20)).apply { marginEnd = context.dp(Space.XS) }
            }
        private val title = makeText(context, "", Type.LABEL_LARGE, ThemeManager.colors.primary).apply { letterSpacing = 0.08f }
        private val hint = makeText(context, "", Type.LABEL_MEDIUM, ThemeManager.colors.onSurfaceVariant)
        private val clear = makeButton(context, "Clear", Btn.TEXT) { actions.onClearFinished() }
        private var section: QueueSection? = null

        init {
            row.addView(chevron)
            row.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(hint)
            row.addView(clear)
            row.setOnClickListener { if (section == QueueSection.FINISHED) actions.onToggleFinished() }
        }

        override fun bind(item: QueueItem) {
            val header = item as QueueHeaderItem
            section = header.section
            title.text = header.title
            hint.text = header.hint.orEmpty()
            hint.visibility = if (header.hint == null) View.GONE else View.VISIBLE
            hint.setTextColor(
                if (header.section ==
                    QueueSection.FINISHED
                ) {
                    ThemeManager.colors.error
                } else {
                    ThemeManager.colors.onSurfaceVariant
                },
            )
            chevron.visibility = if (header.collapsible) View.VISIBLE else View.GONE
            chevron.rotation = if (header.expanded) 0f else -90f
            clear.visibility = if (header.section == QueueSection.FINISHED) View.VISIBLE else View.GONE
            row.isClickable = header.collapsible
            row.background = if (header.collapsible) selectableRipple(ThemeManager.colors.onSurface) else null
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private class StoryHolder(
        context: Context,
        actions: QueueListActions,
        touchHelper: ItemTouchHelper,
    ) : Holder(makeCard(context).withRowParams(bottom = Space.SM)) {
        private val handle =
            context.iconButton(R.drawable.wna_drag_handle, "Reorder", ThemeManager.colors.onSurfaceVariant, IconButtonStyle.Compact)
        private val title =
            makeText(context, "", Type.TITLE_MEDIUM, ThemeManager.colors.onSurface).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
        private val subtitle = makeText(context, "", Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant)
        private val status = makeText(context, "", Type.LABEL_MEDIUM, ThemeManager.colors.onSurfaceVariant)
        private val progress = makeProgress(context, 0f) as ProgressBar
        private var bound: QueueStoryItem? = null
        private val primary = context.iconButton(R.drawable.wna_pause, "Pause", ThemeManager.colors.onSurfaceVariant, IconButtonStyle.Small)
        private val more =
            context.iconButton(
                R.drawable.wna_more_vert,
                "More options",
                ThemeManager.colors.onSurfaceVariant,
                IconButtonStyle.Small,
            )

        init {
            val card = itemView as LinearLayout
            val column =
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(title)
                    addView(subtitle, verticalGap(context, 2))
                    addView(status, verticalGap(context, Space.XS))
                    addView(
                        progress,
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(4)).apply {
                            topMargin =
                                context.dp(Space.SM)
                        },
                    )
                }
            card.addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(handle)
                    addView(
                        column,
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = context.dp(Space.XS) },
                    )
                    addView(primary)
                    addView(more)
                },
            )
            card.isClickable = true
            card.foreground = selectableRipple(ThemeManager.colors.onSurface)
            card.setOnClickListener { bound?.takeIf { it.expandable }?.let { actions.onToggleStory(it.storyId) } }
            primary.setOnClickListener { bound?.let(actions::onStoryPrimary) }
            more.setOnClickListener { bound?.let(actions::onStoryMenu) }
            handle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN && bound?.draggable == true) touchHelper.startDrag(this)
                false
            }
        }

        override fun bind(item: QueueItem) {
            val story = item as QueueStoryItem
            bound = story
            title.text = story.title
            subtitle.text = story.subtitle
            status.text = story.status
            status.setTextColor(toneColor(story.tone))
            progress.progress = if (story.total > 0) story.completed * 100 / story.total else 0
            handle.visibility = if (story.draggable) View.VISIBLE else View.GONE
            val (icon, label, tint) =
                when (story.primaryAction) {
                    QueueAction.PAUSE -> Triple(R.drawable.wna_pause, "Pause", ThemeManager.colors.onSurfaceVariant)
                    QueueAction.RESUME -> Triple(R.drawable.wna_play, "Resume", ThemeManager.colors.primary)
                    QueueAction.RETRY -> Triple(R.drawable.wna_refresh, "Retry failed chapters", ThemeManager.colors.primary)
                    QueueAction.REMOVE, QueueAction.CANCEL ->
                        Triple(
                            R.drawable.wna_close,
                            "Remove from list",
                            ThemeManager.colors.onSurfaceVariant,
                        )
                }
            primary.setImageDrawable(itemView.context.tintedIcon(icon, tint))
            primary.contentDescription = label
            itemView.contentDescription =
                if (story.expandable) "${story.title}, ${if (story.expanded) "hide" else "show"} chapters" else story.title
        }
    }

    private class ChapterHolder(
        context: Context,
        actions: QueueListActions,
    ) : Holder(
            LinearLayout(context)
                .apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(context.dp(Space.LG), context.dp(Space.XS), 0, context.dp(Space.XS))
                }.withRowParams(bottom = 0),
        ) {
        private val dot = View(context)
        private val title =
            makeText(context, "", Type.BODY_MEDIUM, ThemeManager.colors.onSurface).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
        private val status = makeText(context, "", Type.LABEL_SMALL, ThemeManager.colors.onSurfaceVariant)
        private var bound: QueueChapterItem? = null
        private val retry = context.iconButton(R.drawable.wna_refresh, "Retry", ThemeManager.colors.primary, IconButtonStyle.Compact)

        init {
            val row = itemView as LinearLayout
            row.addView(dot, LinearLayout.LayoutParams(context.dp(8), context.dp(8)).apply { marginEnd = context.dp(Space.MD) })
            row.addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(title)
                    addView(status, verticalGap(context, 2))
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            row.addView(retry)
            row.minimumHeight = context.dp(48)
            row.isClickable = true
            row.background = selectableRipple(ThemeManager.colors.onSurface)
            row.setOnClickListener { bound?.let(actions::onChapterMenu) }
            retry.setOnClickListener { bound?.let(actions::onChapterRetry) }
        }

        override fun bind(item: QueueItem) {
            val chapter = item as QueueChapterItem
            bound = chapter
            title.text = chapter.title
            status.text = chapter.status
            val color = toneColor(chapter.tone)
            status.setTextColor(color)
            dot.background = roundedBg(color, itemView.context.dp(4).toFloat())
            retry.visibility = if (chapter.inlineRetry) View.VISIBLE else View.GONE
        }
    }

    private companion object {
        const val TYPE_SUMMARY = 0
        const val TYPE_CHECK = 1
        const val TYPE_FAILED = 2
        const val TYPE_HEADER = 3
        const val TYPE_STORY = 4
        const val TYPE_CHAPTER = 5

        fun toneColor(tone: QueueTone): Int =
            when (tone) {
                QueueTone.NORMAL -> ThemeManager.colors.onSurfaceVariant
                QueueTone.ACTIVE -> ThemeManager.colors.primary
                QueueTone.WARNING, QueueTone.ERROR -> ThemeManager.colors.error
                QueueTone.DONE -> ThemeManager.colors.tertiary
            }

        fun plural(
            count: Int,
            noun: String,
        ): String = if (count == 1) "1 $noun" else "$count ${noun}s"

        fun verticalGap(
            context: Context,
            topDp: Int,
        ) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(topDp)
        }

        fun <T : View> T.withRowParams(
            top: Int = 0,
            bottom: Int,
        ): T =
            apply {
                layoutParams =
                    RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = context.dp(top)
                        bottomMargin = context.dp(bottom)
                    }
            }
    }
}
