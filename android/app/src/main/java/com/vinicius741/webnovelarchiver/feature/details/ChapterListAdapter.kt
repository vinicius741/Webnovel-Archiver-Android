package com.vinicius741.webnovelarchiver.feature.details

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.DownloadJobStatus
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.feature.reader.showReader
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.chapterStatusDot
import com.vinicius741.webnovelarchiver.ui.dot
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.ripple
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.selectableRipple
import com.vinicius741.webnovelarchiver.ui.tintedIcon

/**
 * RecyclerView adapter for the Details chapter list (recycling keeps huge novels from re-inflating
 * every row on each render/filter tick). [chapterStatuses] carries live per-chapter download state
 * from the queue so rows can show spinner/queued/failed feedback beyond the static downloaded dot.
 */
class ChapterListAdapter(
    private val host: ScreenHost,
    private var chapters: List<Pair<Int, Chapter>>,
    private var story: Story,
    private var isEmptyState: Boolean = false,
    private val list: androidx.recyclerview.widget.RecyclerView,
    private var query: String = "",
    private var filter: String = "all",
    private var chapterStatuses: Map<String, DownloadJobStatus> = emptyMap(),
    private var waitingChapterIds: Set<String> = emptySet(),
    private val chipsContainer: ViewGroup,
    private val onPick: (String) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    init {
        // Stable ids: RecyclerView tracks rows by chapter id across DiffUtil updates, not position.
        setHasStableIds(true)
    }

    private val typeEmpty = 0
    private val typeChapter = 1

    /** Skeleton built once in onCreateViewHolder; bind only mutates contents. */
    class RowHolder(
        val row: LinearLayout,
        val statusSlot: FrameLayout,
        val title: TextView,
        val subtitleSlot: LinearLayout,
        val bookmark: ImageView,
    ) : RecyclerView.ViewHolder(row)

    class EmptyHolder(
        val row: LinearLayout,
        val label: TextView,
    ) : RecyclerView.ViewHolder(row)

    fun update(
        chapters: List<Pair<Int, Chapter>>,
        story: Story,
        isEmptyState: Boolean,
        query: String,
        filter: String,
        chapterStatuses: Map<String, DownloadJobStatus>,
        waitingChapterIds: Set<String> = emptySet(),
    ) {
        val previous = this.chapters
        val previousEmpty = this.isEmptyState
        val previousBookmarkId = this.story.lastReadChapterId
        val previousChapterStatuses = this.chapterStatuses
        val previousWaitingChapterIds = this.waitingChapterIds
        this.chapters = chapters
        this.story = story
        this.isEmptyState = isEmptyState
        this.query = query
        this.filter = filter
        this.chapterStatuses = chapterStatuses
        this.waitingChapterIds = waitingChapterIds
        // DiffUtil keyed by chapter id animates changes and rebinds only changed rows; the
        // empty-state toggle changes the tree shape, so it needs a full notify.
        if (previousEmpty != isEmptyState) {
            notifyDataSetChanged()
            return
        }
        if (isEmptyState) {
            notifyDataSetChanged()
            return
        }
        val next = chapters
        DiffUtil
            .calculateDiff(
                object : DiffUtil.Callback() {
                    override fun getOldListSize(): Int = previous.size

                    override fun getNewListSize(): Int = next.size

                    override fun areItemsTheSame(
                        oldItemPosition: Int,
                        newItemPosition: Int,
                    ): Boolean = previous[oldItemPosition].second.id == next[newItemPosition].second.id

                    override fun areContentsTheSame(
                        oldItemPosition: Int,
                        newItemPosition: Int,
                    ): Boolean {
                        val (oldIndex, oldChapter) = previous[oldItemPosition]
                        val (newIndex, newChapter) = next[newItemPosition]
                        val nextBookmarkId = story.lastReadChapterId
                        return oldIndex == newIndex &&
                            oldChapter.id == newChapter.id &&
                            oldChapter.title == newChapter.title &&
                            oldChapter.downloaded == newChapter.downloaded &&
                            oldChapter.downloadedAt == newChapter.downloadedAt &&
                            previousChapterStatuses[oldChapter.id] == chapterStatuses[newChapter.id] &&
                            (oldChapter.id in previousWaitingChapterIds) == (newChapter.id in waitingChapterIds) &&
                            (previousBookmarkId == oldChapter.id) == (nextBookmarkId == newChapter.id)
                    }
                },
            ).dispatchUpdatesTo(this)
    }

    /** Live query/filter so in-place download refreshes can re-filter without a screen rebuild. */
    fun currentQuery(): String = query

    fun currentFilter(): String = filter

    override fun getItemViewType(position: Int): Int = if (isEmptyState) typeEmpty else typeChapter

    override fun getItemId(position: Int): Long =
        if (isEmptyState) {
            RecyclerView.NO_ID
        } else {
            chapters[position]
                .second.id
                .hashCode()
                .toLong()
        }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): RecyclerView.ViewHolder {
        val context = parent.context
        return when (viewType) {
            typeEmpty -> createEmptyHolder(context)
            else -> createChapterHolder(context)
        }
    }

    private fun createEmptyHolder(context: Context): EmptyHolder {
        val row =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(context.dp(Space.MD), context.dp(Space.SM + 2), context.dp(Space.XS + 2), context.dp(Space.SM + 2))
                layoutParams =
                    LinearLayout
                        .LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { bottomMargin = context.dp(Space.XS + 2) }
            }
        val label =
            makeText(context, "", Type.LABEL_MEDIUM, ThemeManager.colors.onSurfaceVariant).apply {
                gravity = Gravity.CENTER
                setPadding(context.dp(Space.LG), context.dp(Space.LG), context.dp(Space.LG), context.dp(Space.LG))
            }
        row.addView(label)
        row.isClickable = false
        return EmptyHolder(row, label)
    }

    private fun createChapterHolder(context: Context): RowHolder {
        val row =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = context.dp(64)
                setPadding(context.dp(Space.LG), context.dp(Space.SM), context.dp(Space.SM), context.dp(Space.SM))
                layoutParams =
                    LinearLayout
                        .LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { bottomMargin = context.dp(Space.XS) }
            }
        // The status slot's leading child is swapped in bind; the rest of the skeleton is reused.
        val statusSlot = chapterStatusSlot(context, host.dot(ThemeManager.colors.outlineVariant))
        val title =
            makeText(context, "", Type.TITLE_SMALL, ThemeManager.colors.onSurface).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
            }
        val subtitleSlot =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
        val titleColumn =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(context.dp(Space.XS), 0, context.dp(Space.XS), 0)
                addView(title)
                addView(subtitleSlot)
            }
        val bookmark =
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(context.dp(Space.SM + 2), context.dp(Space.SM + 2), context.dp(Space.SM + 2), context.dp(Space.SM + 2))
                background = selectableRipple(ThemeManager.colors.onSurface)
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(context.dp(44), context.dp(44))
            }
        row.addView(statusSlot)
        row.addView(titleColumn)
        row.addView(bookmark)
        return RowHolder(row, statusSlot, title, subtitleSlot, bookmark)
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
    ) {
        val (index, chapter) = chapters[position]
        when (holder) {
            is EmptyHolder -> {
                holder.label.text = chapter.title
                return
            }
            is RowHolder -> bindChapterHolder(holder, index, chapter)
        }
    }

    private fun bindChapterHolder(
        holder: RowHolder,
        index: Int,
        chapter: Chapter,
    ) {
        val context: Context = holder.row.context
        // Not-downloaded chapters aren't tappable — including downloading/queued/failed ones, whose
        // live status still shows via the dot/spinner/subtitle.
        val openable = chapter.downloaded
        val radiusPx = context.dp(Space.SM).toFloat()
        holder.row.apply {
            background =
                if (openable) {
                    ripple(roundedBg(ThemeManager.colors.elevation1, radiusPx), radiusPx, ThemeManager.colors.onSurface)
                } else {
                    roundedBg(ThemeManager.colors.elevation1, radiusPx)
                }
            isClickable = openable
            isFocusable = openable
            setOnClickListener { if (openable) host.showReader(story.id, chapter.id) }
        }
        // Queue status takes precedence over the static downloaded flag.
        val liveStatus = chapterStatuses[chapter.id]
        val waitingForDelay = chapter.id in waitingChapterIds
        setStatusLeading(holder.statusSlot, liveStatus, chapter.downloaded, waitingForDelay, context)
        holder.title.text = ChapterRowPlanning.displayTitle(chapter.title)
        // Dim the title when not openable so the row reads as disabled.
        holder.title.setTextColor(if (openable) ThemeManager.colors.onSurface else ThemeManager.colors.onSurfaceVariant)
        holder.row.contentDescription =
            "Chapter ${ChapterRowPlanning.indexLabel(index)}, ${ChapterRowPlanning.displayTitle(chapter.title)}"
        holder.subtitleSlot.removeAllViews()
        holder.subtitleSlot.addView(
            subtitleText(index, liveStatus, chapter.downloaded, chapter.downloadedAt, waitingForDelay, context),
        )
        // One-tap bookmark: outline by default, filled when this is the novel's bookmark.
        val isBookmarked = story.lastReadChapterId == chapter.id
        holder.bookmark.setImageDrawable(
            context.tintedIcon(
                if (isBookmarked) R.drawable.wna_bookmark else R.drawable.wna_bookmark_outline,
                if (isBookmarked) ThemeManager.colors.primary else ThemeManager.colors.onSurfaceVariant,
            ),
        )
        holder.bookmark.contentDescription = if (isBookmarked) "Clear bookmark" else "Bookmark chapter"
        holder.bookmark.setOnClickListener {
            host.toggleChapterBookmark(story, chapter, list, chipsContainer, filter, query, onPick)
        }
    }

    /** Swaps [statusSlot]'s leading child; colors are baked into Views, so they must be swapped. */
    private fun setStatusLeading(
        statusSlot: FrameLayout,
        liveStatus: DownloadJobStatus?,
        downloaded: Boolean,
        waitingForDelay: Boolean,
        context: Context,
    ) {
        val desired: View =
            when (liveStatus) {
                DownloadJobStatus.Downloading -> if (waitingForDelay) host.dot(ThemeManager.colors.secondary) else chapterSpinner(context)
                DownloadJobStatus.Pending -> host.dot(ThemeManager.colors.primary)
                DownloadJobStatus.Failed -> host.dot(ThemeManager.colors.error)
                else -> host.chapterStatusDot(downloaded)
            }
        // Keep a running spinner across rebinds; dots (color baked in) always swap to recolor.
        val current = statusSlot.getChildAt(0)
        val keepSpinner = desired is ProgressBar && current is ProgressBar
        if (keepSpinner) return
        statusSlot.removeAllViews()
        statusSlot.addView(
            desired,
            FrameLayout.LayoutParams(
                context.dp(if (desired is ProgressBar) 16 else 10),
                context.dp(if (desired is ProgressBar) 16 else 10),
                Gravity.START or Gravity.CENTER_VERTICAL,
            ),
        )
    }

    private fun subtitleText(
        index: Int,
        liveStatus: DownloadJobStatus?,
        downloaded: Boolean,
        downloadedAt: Long?,
        waitingForDelay: Boolean,
        context: Context,
    ): TextView {
        val label = ChapterRowPlanning.metadataLabel(index, liveStatus, downloaded, downloadedAt, waitingForDelay)
        val color =
            when (liveStatus) {
                DownloadJobStatus.Downloading -> if (waitingForDelay) ThemeManager.colors.secondary else ThemeManager.colors.primary
                DownloadJobStatus.Failed -> ThemeManager.colors.error
                // Quiet metadata so the title stays the focus.
                else -> ThemeManager.colors.onSurfaceVariant
            }
        return makeText(context, label, Type.CAPTION, color).apply {
            includeFontPadding = false
            setPadding(0, context.dp(Space.XS), 0, 0)
        }
    }

    override fun getItemCount(): Int = chapters.size
}
