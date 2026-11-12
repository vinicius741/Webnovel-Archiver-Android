package com.vinicius741.webnovelarchiver.feature.downloads

import android.content.Context
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.vinicius741.webnovelarchiver.download.QueueSection
import com.vinicius741.webnovelarchiver.download.SourceCheckCard

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
    /** Loads a novel's current cover into a card's thumbnail; tapping it opens the cover full screen. */
    private val bindCover: (ImageView, String) -> Unit,
) : RecyclerView.Adapter<QueueHolder>() {
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
    ): QueueHolder =
        when (viewType) {
            TYPE_SUMMARY -> QueueSummaryHolder(context)
            TYPE_CHECK -> QueueCheckHolder(context, actions)
            TYPE_FAILED -> QueueFailedHolder(context, actions)
            TYPE_HEADER -> QueueHeaderHolder(context, actions)
            TYPE_STORY -> QueueStoryHolder(context, actions, touchHelper, bindCover)
            else -> QueueChapterHolder(context, actions)
        }

    override fun onBindViewHolder(
        holder: QueueHolder,
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

    private companion object {
        const val TYPE_SUMMARY = 0
        const val TYPE_CHECK = 1
        const val TYPE_FAILED = 2
        const val TYPE_HEADER = 3
        const val TYPE_STORY = 4
        const val TYPE_CHAPTER = 5
    }
}
