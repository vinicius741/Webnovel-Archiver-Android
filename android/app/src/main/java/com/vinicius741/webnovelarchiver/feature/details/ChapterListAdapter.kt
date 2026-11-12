package com.vinicius741.webnovelarchiver.feature.details

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
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
import com.vinicius741.webnovelarchiver.feature.narration.narration
import com.vinicius741.webnovelarchiver.feature.reader.showReader
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.dot
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.ripple
import com.vinicius741.webnovelarchiver.ui.selectableRipple
import com.vinicius741.webnovelarchiver.ui.tintedIcon

/**
 * The Chapters tab's rows (recycling keeps huge novels from inflating every row on each filter or
 * download tick). [show] re-filters and diffs by chapter id, so a download event rebinds only the
 * rows whose state changed. Rows are transparent; [com.vinicius741.webnovelarchiver.ui.CardListDecoration]
 * draws the card behind them.
 */
@Suppress("TooManyFunctions") // RecyclerView adapter: overrides plus one bind helper per row part.
class ChapterListAdapter internal constructor(
    private val host: ScreenHost,
    private var story: Story,
    private val onToggleBookmark: (Chapter) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    init {
        // Stable ids: RecyclerView tracks rows by chapter id across DiffUtil updates, not position.
        setHasStableIds(true)
    }

    private var chapters: List<Pair<Int, Chapter>> = emptyList()
    private var query = ""
    private var filter = "all"
    private var chapterStatuses: Map<String, DownloadJobStatus> = emptyMap()
    private var waitingChapterIds: Set<String> = emptySet()

    /** Skeleton built once in onCreateViewHolder; bind only mutates contents. */
    class RowHolder(
        val row: LinearLayout,
        val statusSlot: FrameLayout,
        val title: TextView,
        val subtitle: TextView,
        val bookmark: ImageView,
    ) : RecyclerView.ViewHolder(row)

    class EmptyHolder(
        row: LinearLayout,
    ) : RecyclerView.ViewHolder(row)

    /** Shows [story]'s chapters through [query] and [filter], diffing against what is on screen. */
    fun show(
        story: Story,
        query: String,
        filter: String,
        statuses: Map<String, DownloadJobStatus> = chapterStatuses,
        waiting: Set<String> = waitingChapterIds,
    ) {
        val previous = chapters
        val previousBookmarkId = this.story.lastReadChapterId
        val previousStatuses = chapterStatuses
        val previousWaiting = waitingChapterIds
        val next = filterDetailsChapters(story, query, filter)
        val wasEmpty = previous.isEmpty()
        this.story = story
        this.query = query
        this.filter = filter
        chapterStatuses = statuses
        waitingChapterIds = waiting
        chapters = next
        // The empty row changes the tree shape, so it needs a full notify.
        if (wasEmpty || next.isEmpty()) {
            @Suppress("NotifyDataSetChanged")
            notifyDataSetChanged()
            return
        }
        val bookmarkId = story.lastReadChapterId
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
                        val (oldIndex, old) = previous[oldItemPosition]
                        val (newIndex, new) = next[newItemPosition]
                        return oldIndex == newIndex &&
                            old.title == new.title &&
                            old.downloaded == new.downloaded &&
                            old.downloadedAt == new.downloadedAt &&
                            previousStatuses[old.id] == statuses[new.id] &&
                            (old.id in previousWaiting) == (new.id in waiting) &&
                            (previousBookmarkId == old.id) == (bookmarkId == new.id)
                    }
                },
            ).dispatchUpdatesTo(this)
    }

    /** A download event: same query and filter, new story and queue state. */
    fun refresh(
        story: Story,
        statuses: Map<String, DownloadJobStatus>,
        waiting: Set<String>,
    ) = show(story, query, filter, statuses, waiting)

    /** Adapter position of [chapterId] among the shown rows, or -1. */
    fun positionOf(chapterId: String): Int = chapters.indexOfFirst { it.second.id == chapterId }

    /** Number of chapters shown (0 while the "No chapters match" row is up). */
    fun shownCount(): Int = chapters.size

    override fun getItemViewType(position: Int): Int = if (chapters.isEmpty()) TYPE_EMPTY else TYPE_CHAPTER

    override fun getItemId(position: Int): Long =
        if (chapters.isEmpty()) {
            EMPTY_ID
        } else {
            chapters[position]
                .second.id
                .hashCode()
                .toLong()
        }

    override fun getItemCount(): Int = if (chapters.isEmpty()) 1 else chapters.size

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): RecyclerView.ViewHolder =
        if (viewType == TYPE_EMPTY) {
            createEmptyHolder(parent.context)
        } else {
            createChapterHolder(parent.context)
        }

    private fun createEmptyHolder(context: Context): EmptyHolder {
        val row =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(
                    context.dp(Spacing.XL),
                    context.dp(Spacing.XL + Spacing.SM),
                    context.dp(Spacing.XL),
                    context.dp(Spacing.XL + Spacing.SM),
                )
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(
                    makeText(context, "No chapters match", Type.TITLE_MEDIUM, ThemeManager.colors.onSurface).apply {
                        gravity =
                            Gravity.CENTER
                    },
                )
                addView(
                    makeText(context, "Try another search or filter.", Type.BODY_MEDIUM, ThemeManager.colors.onSurfaceVariant).apply {
                        gravity = Gravity.CENTER
                        setPadding(0, context.dp(Spacing.XS), 0, 0)
                    },
                )
            }
        return EmptyHolder(row)
    }

    private fun createChapterHolder(context: Context): RowHolder {
        val row =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = context.dp(ROW_MIN_HEIGHT_DP)
                setPadding(context.dp(Spacing.LG), context.dp(Spacing.SM), context.dp(Spacing.XS), context.dp(Spacing.SM))
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        // The status slot's leading child is swapped in bind; the rest of the skeleton is reused.
        val statusSlot = chapterStatusSlot(context, host.dot(ThemeManager.colors.faint))
        val title =
            makeText(context, "", Type.TITLE_SMALL, ThemeManager.colors.onSurface).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
            }
        val subtitle =
            makeText(context, "", Type.CAPTION, ThemeManager.colors.onSurfaceVariant).apply {
                includeFontPadding = false
                maxLines = 2
                setPadding(0, context.dp(Spacing.XS), 0, 0)
            }
        val titleColumn =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(context.dp(Spacing.SM + 2), 0, context.dp(Spacing.XS), 0)
                addView(title)
                addView(subtitle)
            }
        val bookmark =
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(context.dp(Spacing.SM + 2), context.dp(Spacing.SM + 2), context.dp(Spacing.SM + 2), context.dp(Spacing.SM + 2))
                background = selectableRipple(ThemeManager.colors.onSurface)
                isClickable = true
                isFocusable = true
            }
        row.addView(statusSlot)
        row.addView(titleColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(bookmark, LinearLayout.LayoutParams(context.dp(BOOKMARK_TAP_DP), context.dp(BOOKMARK_TAP_DP)))
        return RowHolder(row, statusSlot, title, subtitle, bookmark)
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
    ) {
        if (holder !is RowHolder) return
        val (index, chapter) = chapters[position]
        bindChapterHolder(holder, index, chapter)
    }

    private fun bindChapterHolder(
        holder: RowHolder,
        index: Int,
        chapter: Chapter,
    ) {
        val context: Context = holder.row.context
        val colors = ThemeManager.colors
        // Not-downloaded chapters aren't readable; their live status still shows in the row.
        val openable = chapter.downloaded
        val isBookmarked = story.lastReadChapterId == chapter.id
        holder.row.background = if (isBookmarked) bookmarkRowBackground(context) else selectableRipple(colors.onSurface)
        holder.row.isClickable = openable
        holder.row.isFocusable = openable
        holder.row.setOnClickListener { if (openable) host.showReader(story.id, chapter.id) }
        val liveStatus = chapterStatuses[chapter.id]
        val waitingForDelay = chapter.id in waitingChapterIds
        setStatusLeading(holder.statusSlot, liveStatus, chapter.downloaded, waitingForDelay, context)
        val displayTitle = ChapterRowPlanning.displayTitle(chapter.title)
        holder.title.text = displayTitle
        holder.title.setTextColor(if (openable) colors.onSurface else colors.onSurfaceVariant)
        holder.row.contentDescription = "Chapter ${ChapterRowPlanning.indexLabel(index)}, $displayTitle"
        bindSubtitle(holder.subtitle, index, liveStatus, chapter, waitingForDelay)
        holder.bookmark.setImageDrawable(
            context.tintedIcon(
                if (isBookmarked) R.drawable.wna_bookmark else R.drawable.wna_bookmark_outline,
                if (isBookmarked) colors.bookmark else colors.faint,
            ),
        )
        holder.bookmark.contentDescription = if (isBookmarked) "Clear bookmark" else "Bookmark chapter"
        holder.bookmark.setOnClickListener { onToggleBookmark(chapter) }
    }

    /** The bookmarked row: a faint ivory wash with an ivory bar on its leading edge. */
    private fun bookmarkRowBackground(context: Context): android.graphics.drawable.Drawable {
        val ivory = ThemeManager.colors.bookmark
        val wash = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(ivory and BOOKMARK_WASH_MASK, Color.TRANSPARENT))
        val layers = LayerDrawable(arrayOf(wash, ColorDrawable(ivory)))
        layers.setLayerWidth(1, context.dp(BOOKMARK_BAR_DP))
        layers.setLayerGravity(1, Gravity.START or Gravity.FILL_VERTICAL)
        return ripple(layers, 0f, ThemeManager.colors.onSurface)
    }

    /** Swaps [statusSlot]'s leading child; colors are baked into Views, so they must be swapped. */
    private fun setStatusLeading(
        statusSlot: FrameLayout,
        liveStatus: DownloadJobStatus?,
        downloaded: Boolean,
        waitingForDelay: Boolean,
        context: Context,
    ) {
        val colors = ThemeManager.colors
        val desired: View =
            when (liveStatus) {
                DownloadJobStatus.Downloading -> if (waitingForDelay) host.dot(colors.secondary) else chapterSpinner(context)
                DownloadJobStatus.Pending -> host.dot(colors.onSurfaceVariant)
                DownloadJobStatus.Failed -> host.dot(colors.error)
                else -> host.dot(if (downloaded) colors.primary else colors.faint)
            }
        // Keep a running spinner across rebinds; dots (color baked in) always swap to recolor.
        val current = statusSlot.getChildAt(0)
        if (desired is ProgressBar && current is ProgressBar) return
        statusSlot.removeAllViews()
        val size = context.dp(if (desired is ProgressBar) SPINNER_DP else DOT_DP)
        statusSlot.addView(desired, FrameLayout.LayoutParams(size, size, Gravity.START or Gravity.CENTER_VERTICAL))
    }

    private fun bindSubtitle(
        view: TextView,
        index: Int,
        liveStatus: DownloadJobStatus?,
        chapter: Chapter,
        waitingForDelay: Boolean,
    ) {
        val colors = ThemeManager.colors
        val label =
            ChapterRowPlanning.metadataLabel(
                index,
                liveStatus,
                chapter.downloaded,
                chapter.downloadedAt,
                waitingForDelay,
                patreonCopy = chapter.isPatreonCopy,
                removedFromSource = chapter.isRemovedFromSource,
                hasAudio = host.narration.store.pack(story.id, chapter.id) != null,
            )
        val spanned = SpannableString(label)
        // Generated audio reads in gold, like the Audio tab.
        val audioAt = label.indexOf(AUDIO_LABEL)
        if (audioAt >=
            0
        ) {
            spanned.setSpan(
                ForegroundColorSpan(colors.primary),
                audioAt,
                audioAt + AUDIO_LABEL.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        view.text = spanned
        view.setTextColor(
            when (liveStatus) {
                DownloadJobStatus.Downloading -> if (waitingForDelay) colors.secondary else colors.primary
                DownloadJobStatus.Failed -> colors.error
                else -> colors.onSurfaceVariant
            },
        )
    }

    private companion object {
        const val TYPE_EMPTY = 0
        const val TYPE_CHAPTER = 1
        const val EMPTY_ID = Long.MIN_VALUE
        const val ROW_MIN_HEIGHT_DP = 64
        const val BOOKMARK_TAP_DP = 44
        const val BOOKMARK_BAR_DP = 3
        const val BOOKMARK_WASH_MASK = 0x17FFFFFF
        const val SPINNER_DP = 14
        const val DOT_DP = 8
        const val AUDIO_LABEL = "Audio"
    }
}

/**
 * Small indeterminate spinner sized to match the chapter status dot, used in place of the dot for
 * a chapter currently being fetched. Primary-tinted so it reads as "active work".
 */
internal fun chapterSpinner(context: Context): View {
    val size = context.dp(16)
    return ProgressBar(context, null, android.R.attr.progressBarStyleSmall).apply {
        indeterminateTintList = ColorStateList.valueOf(ThemeManager.colors.primary)
        layoutParams = FrameLayout.LayoutParams(size, size)
    }
}

/** Fixed-width leading slot shared by dots and spinners so status changes never move row text. */
internal fun chapterStatusSlot(
    context: Context,
    status: View,
): FrameLayout =
    FrameLayout(context).apply {
        layoutParams = LinearLayout.LayoutParams(context.dp(18), context.dp(18))
        val isSpinner = status is ProgressBar
        addView(
            status,
            FrameLayout.LayoutParams(
                context.dp(if (isSpinner) 16 else 10),
                context.dp(if (isSpinner) 16 else 10),
                Gravity.START or Gravity.CENTER_VERTICAL,
            ),
        )
    }
