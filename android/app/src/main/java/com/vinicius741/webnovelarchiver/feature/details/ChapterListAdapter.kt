package com.vinicius741.webnovelarchiver.feature.details

import android.content.Context
import android.content.res.ColorStateList
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
 * RecyclerView adapter for the Details chapter list (Speed S1). Replaces the per-render
 * `LinearLayout.addView` loop with view recycling, so novels with hundreds/thousands of chapters
 * no longer inflate one row each on every render/filter tick.
 *
 * Row layout: status leading · title + compact index/state metadata · bookmark. Removing the
 * separate index column gives numeric source titles (e.g. "13.11 …") one clear visual anchor, while
 * the explicitly labeled index preserves the chapter's position in the full list. Metadata also
 * carries the live queue state or a quiet "Downloaded MMM d, yyyy" when known.
 *
 * [chapterStatuses] carries live per-chapter download state from the queue (see
 * [com.vinicius741.webnovelarchiver.download.DownloadDetailsPlanning.chapterJobStatuses]) so a row can
 * show real-time feedback — an indeterminate spinner for a chapter being fetched, a queued dot, or a
 * "Failed" state — in addition to the static downloaded/not-downloaded dot.
 *
 * View helpers are Context extensions (`Context.dp`, `Context.tintedIcon`, `Context.makeText`); the
 * adapter resolves the Context from the parent view and threads it through.
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
    private val chipsContainer: ViewGroup,
    private val onPick: (String) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    init {
        // U1: stable ids let RecyclerView track rows across DiffUtil updates (and animations) by
        // chapter id instead of position, so a filter/download tick no longer invalidates everything.
        setHasStableIds(true)
    }

    /** Item types: a distinct, cheaply-bound empty-state row vs the full chapter row. */
    private val typeEmpty = 0
    private val typeChapter = 1

    /** Holder for the normal chapter row. Holds the static skeleton built once in onCreateViewHolder;
     *  onBindViewHolder only mutates contents (index, title, status, subtitle, bookmark). */
    class RowHolder(
        val row: LinearLayout,
        val statusSlot: FrameLayout,
        val title: TextView,
        val subtitleSlot: LinearLayout,
        val bookmark: ImageView,
    ) : RecyclerView.ViewHolder(row)

    /** Holder for the empty-state row (a single centered label). */
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
    ) {
        val previous = this.chapters
        val previousEmpty = this.isEmptyState
        val previousBookmarkId = this.story.lastReadChapterId
        val previousChapterStatuses = this.chapterStatuses
        this.chapters = chapters
        this.story = story
        this.isEmptyState = isEmptyState
        this.query = query
        this.filter = filter
        this.chapterStatuses = chapterStatuses
        // U1: prefer a DiffUtil pass keyed by chapter id so insertions/removals/reorders animate and
        // only changed rows rebind. When the empty-state toggles, the whole tree changes shape, so
        // fall back to a full notifyDataSetChanged in that one transition.
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
                        // Content identity: position in the story, the displayed title, download state
                        // (flag + timestamp), the live queue status, and whether this is bookmarked.
                        val nextBookmarkId = story.lastReadChapterId
                        return oldIndex == newIndex &&
                            oldChapter.id == newChapter.id &&
                            oldChapter.title == newChapter.title &&
                            oldChapter.downloaded == newChapter.downloaded &&
                            oldChapter.downloadedAt == newChapter.downloadedAt &&
                            previousChapterStatuses[oldChapter.id] == chapterStatuses[newChapter.id] &&
                            (previousBookmarkId == oldChapter.id) == (nextBookmarkId == newChapter.id)
                    }
                },
            ).dispatchUpdatesTo(this)
    }

    /** The query/filter currently applied so the in-place download refresh can re-filter against the
     *  user's live view without forcing a full screen rebuild. */
    fun currentQuery(): String = query

    fun currentFilter(): String = filter

    override fun getItemViewType(position: Int): Int = if (isEmptyState) typeEmpty else typeChapter

    override fun getItemId(position: Int): Long {
        // U1: stable id keyed by chapter id; the empty-state row uses a fixed sentinel.
        return if (isEmptyState) {
            RecyclerView.NO_ID
        } else {
            chapters[position]
                .second.id
                .hashCode()
                .toLong()
        }
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
        // U1: build the row skeleton ONCE here. The status slot is a fixed FrameLayout whose child
        // is swapped in bind; the title column carries the title + a compact metadata line; the
        // bookmark icon is
        // reused and only re-tinted in bind.
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
        // A chapter that isn't downloaded can't be read yet, so it isn't tappable: no ripple, no click
        // listener. The existing outlineVariant status dot + muted title still show which chapters
        // remain to be fetched. (A downloading/queued/failed chapter also has downloaded == false, so
        // it is blocked here too — its live status is still conveyed by the dot/spinner/subtitle.)
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
        // Live status from the download queue takes precedence over the static downloaded flag, so
        // an in-flight/queued/failed chapter shows real-time feedback rather than "not downloaded".
        val liveStatus = chapterStatuses[chapter.id]
        // U1: swap only the leading child of the fixed status slot instead of rebuilding the row.
        setStatusLeading(holder.statusSlot, liveStatus, chapter.downloaded, context)
        holder.title.text = ChapterRowPlanning.displayTitle(chapter.title)
        // Dim the title when the chapter can't be opened so the row reads as disabled, matching the
        // faint status dot used for non-downloaded chapters.
        holder.title.setTextColor(if (openable) ThemeManager.colors.onSurface else ThemeManager.colors.onSurfaceVariant)
        holder.row.contentDescription =
            "Chapter ${ChapterRowPlanning.indexLabel(index)}, ${ChapterRowPlanning.displayTitle(chapter.title)}"
        // U1: rebuild only the single subtitle TextView (cheap) inside the reused subtitle slot.
        holder.subtitleSlot.removeAllViews()
        holder.subtitleSlot.addView(
            subtitleText(index, liveStatus, chapter.downloaded, chapter.downloadedAt, context),
        )
        // One-tap bookmark (replaces the per-chapter three-dot overflow): empty outline by default,
        // filled + primary-tinted when this chapter is the novel's bookmark. Tapping toggles it.
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

    /** U1: replace the leading child of the fixed [statusSlot] with the view for [liveStatus]. Cheaper
     *  than rebuilding the row; the dot/spinner color is baked into the View so it must be swapped. */
    private fun setStatusLeading(
        statusSlot: FrameLayout,
        liveStatus: DownloadJobStatus?,
        downloaded: Boolean,
        context: Context,
    ) {
        val desired: View =
            when (liveStatus) {
                DownloadJobStatus.Downloading -> chapterSpinner(context)
                DownloadJobStatus.Pending -> host.dot(ThemeManager.colors.primary)
                DownloadJobStatus.Failed -> host.dot(ThemeManager.colors.error)
                else -> host.chapterStatusDot(downloaded)
            }
        // Keep an already-running spinner rather than replacing it on every progress rebind; for
        // dots (color baked in) always swap so a status change recolors correctly.
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
        context: Context,
    ): TextView {
        val label = ChapterRowPlanning.metadataLabel(index, liveStatus, downloaded, downloadedAt)
        val color =
            when (liveStatus) {
                DownloadJobStatus.Downloading -> ThemeManager.colors.primary
                DownloadJobStatus.Failed -> ThemeManager.colors.error
                // Quiet metadata for download date / offline cue so the title stays the focus.
                else -> ThemeManager.colors.onSurfaceVariant
            }
        return makeText(context, label, Type.CAPTION, color).apply {
            includeFontPadding = false
            setPadding(0, context.dp(Space.XS), 0, 0)
        }
    }

    override fun getItemCount(): Int = chapters.size
}

/**
 * Small indeterminate spinner sized to match the chapter status dot (10dp), used in place of the
 * dot for a chapter currently being fetched. Primary-tinted so it reads as "active work".
 */
private fun chapterSpinner(context: Context): android.view.View {
    val t = ThemeManager.current
    val size = context.dp(16)
    return ProgressBar(context, null, android.R.attr.progressBarStyleSmall).apply {
        indeterminateTintList = ColorStateList.valueOf(t.colors.primary)
        layoutParams = FrameLayout.LayoutParams(size, size)
    }
}

/** Fixed-width leading slot shared by dots and spinners so status changes never move row text. */
private fun chapterStatusSlot(
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
