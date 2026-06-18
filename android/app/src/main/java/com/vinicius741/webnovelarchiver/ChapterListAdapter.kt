package com.vinicius741.webnovelarchiver

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
import androidx.recyclerview.widget.RecyclerView
import com.vinicius741.webnovelarchiver.core.Chapter
import com.vinicius741.webnovelarchiver.core.DownloadJobStatus
import com.vinicius741.webnovelarchiver.core.Story
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.chapterStatusDot
import com.vinicius741.webnovelarchiver.ui.dot
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.ripple
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.sanitizeTitle
import com.vinicius741.webnovelarchiver.ui.selectableRipple
import com.vinicius741.webnovelarchiver.ui.tintedIcon

/** A single existing View exposed as a RecyclerView item so compact Details can keep one scrolling
 * surface without nesting the chapter list inside a ScrollView. */
class DetailsHeaderAdapter(
    private val header: View,
) : RecyclerView.Adapter<DetailsHeaderAdapter.HeaderHolder>() {
    class HeaderHolder(
        val container: FrameLayout,
    ) : RecyclerView.ViewHolder(container)

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): HeaderHolder =
        HeaderHolder(
            FrameLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            },
        )

    override fun onBindViewHolder(
        holder: HeaderHolder,
        position: Int,
    ) {
        (header.parent as? ViewGroup)?.removeView(header)
        holder.container.removeAllViews()
        holder.container.addView(header, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    override fun getItemCount(): Int = 1
}

/**
 * RecyclerView adapter for the Details chapter list (Speed S1). Replaces the per-render
 * `LinearLayout.addView` loop with view recycling, so novels with hundreds/thousands of chapters
 * no longer inflate one row each on every render/filter tick. Row layout + styling mirrors the
 * previous `chapterRow` builder exactly (status dot, sanitized title, "Available Offline", overflow).
 *
 * [chapterStatuses] carries live per-chapter download state from the queue (see
 * [com.vinicius741.webnovelarchiver.core.DownloadDetailsPlanning.chapterJobStatuses]) so a row can
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
) : RecyclerView.Adapter<ChapterListAdapter.RowHolder>() {
    class RowHolder(
        val row: LinearLayout,
    ) : RecyclerView.ViewHolder(row)

    fun update(
        chapters: List<Pair<Int, Chapter>>,
        story: Story,
        isEmptyState: Boolean,
        query: String,
        filter: String,
        chapterStatuses: Map<String, DownloadJobStatus>,
    ) {
        this.chapters = chapters
        this.story = story
        this.isEmptyState = isEmptyState
        this.query = query
        this.filter = filter
        this.chapterStatuses = chapterStatuses
        notifyDataSetChanged()
    }

    /** The query/filter currently applied so the in-place download refresh can re-filter against the
     *  user's live view without forcing a full screen rebuild. */
    fun currentQuery(): String = query

    fun currentFilter(): String = filter

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): RowHolder {
        val context = parent.context
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
        return RowHolder(row)
    }

    override fun onBindViewHolder(
        holder: RowHolder,
        position: Int,
    ) {
        val (index, chapter) = chapters[position]
        val context: Context = holder.row.context
        val row = holder.row
        row.removeAllViews()

        if (isEmptyState) {
            row.addView(
                makeText(context, chapter.title, Type.LABEL_MEDIUM, ThemeManager.colors.onSurfaceVariant).apply {
                    gravity = Gravity.CENTER
                    setPadding(context.dp(Space.LG), context.dp(Space.LG), context.dp(Space.LG), context.dp(Space.LG))
                },
            )
            row.isClickable = false
            return
        }

        val radiusPx = context.dp(Space.SM).toFloat()
        row.apply {
            background = ripple(roundedBg(ThemeManager.colors.elevation1, radiusPx), radiusPx, ThemeManager.colors.onSurface)
            isClickable = true
            isFocusable = true
            setOnClickListener { host.showReader(story.id, chapter.id) }
        }
        // Live status from the download queue takes precedence over the static downloaded flag, so
        // an in-flight/queued/failed chapter shows real-time feedback rather than "not downloaded".
        val liveStatus = chapterStatuses[chapter.id]
        val statusLeading: android.view.View =
            when (liveStatus) {
                DownloadJobStatus.Downloading -> chapterSpinner(context)
                DownloadJobStatus.Pending -> host.dot(ThemeManager.colors.primary)
                DownloadJobStatus.Failed -> host.dot(ThemeManager.colors.error)
                else -> host.chapterStatusDot(chapter.downloaded)
            }
        // Keep every row's text aligned while allowing the active spinner to be larger than the
        // 10dp status dot. Giving the ProgressBar its own FrameLayout slot also avoids passing its
        // FrameLayout.LayoutParams directly to this LinearLayout (which previously dropped the
        // trailing gap and pulled the downloading chapter title to the left).
        row.addView(chapterStatusSlot(context, statusLeading))
        row.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(
                    makeText(
                        context,
                        "${index + 1}. ${sanitizeTitle(chapter.title)}",
                        Type.TITLE_SMALL,
                        ThemeManager.colors.onSurface,
                    ).apply {
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                    },
                )
                // Subtitle: live queue state first (it's more immediate), then the static offline badge.
                when (liveStatus) {
                    DownloadJobStatus.Downloading ->
                        addView(
                            makeText(context, "Downloading…", Type.LABEL_SMALL, ThemeManager.colors.primary).apply {
                                setPadding(0, context.dp(2), 0, 0)
                            },
                        )
                    DownloadJobStatus.Pending ->
                        addView(
                            makeText(context, "Queued", Type.LABEL_SMALL, ThemeManager.colors.onSurfaceVariant).apply {
                                setPadding(0, context.dp(2), 0, 0)
                            },
                        )
                    DownloadJobStatus.Failed ->
                        addView(
                            makeText(context, "Download failed", Type.LABEL_SMALL, ThemeManager.colors.error).apply {
                                setPadding(0, context.dp(2), 0, 0)
                            },
                        )
                    else ->
                        if (chapter.downloaded) {
                            addView(
                                makeText(context, "Available Offline", Type.LABEL_SMALL, ThemeManager.colors.secondary).apply {
                                    setPadding(0, context.dp(2), 0, 0)
                                },
                            )
                        }
                }
            },
        )
        // One-tap bookmark (replaces the per-chapter three-dot overflow): empty outline by default,
        // filled + primary-tinted when this chapter is the novel's bookmark. Tapping toggles it.
        val isBookmarked = story.lastReadChapterId == chapter.id
        row.addView(
            ImageView(context).apply {
                setImageDrawable(
                    context.tintedIcon(
                        if (isBookmarked) R.drawable.wna_bookmark else R.drawable.wna_bookmark_outline,
                        if (isBookmarked) ThemeManager.colors.primary else ThemeManager.colors.onSurfaceVariant,
                    ),
                )
                contentDescription = if (isBookmarked) "Clear bookmark" else "Bookmark chapter"
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(context.dp(Space.SM + 2), context.dp(Space.SM + 2), context.dp(Space.SM + 2), context.dp(Space.SM + 2))
                background = selectableRipple(ThemeManager.colors.onSurface)
                isClickable = true
                isFocusable = true
                setOnClickListener { host.toggleChapterBookmark(story, chapter, list, query, filter) }
                layoutParams = LinearLayout.LayoutParams(context.dp(44), context.dp(44))
            },
        )
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
        layoutParams = LinearLayout.LayoutParams(context.dp(20), context.dp(18))
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
