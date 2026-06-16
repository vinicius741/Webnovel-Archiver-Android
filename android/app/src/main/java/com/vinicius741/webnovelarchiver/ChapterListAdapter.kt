package com.vinicius741.webnovelarchiver

import android.content.Context
import android.content.res.ColorStateList
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
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
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.ripple
import com.vinicius741.webnovelarchiver.ui.sanitizeTitle
import com.vinicius741.webnovelarchiver.ui.selectableRipple
import com.vinicius741.webnovelarchiver.ui.tintedIcon

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
    private val chapters: List<Pair<Int, Chapter>>,
    private val story: Story,
    private val isEmptyState: Boolean = false,
    private val list: androidx.recyclerview.widget.RecyclerView,
    private val query: String = "",
    private val filter: String = "all",
    private val chapterStatuses: Map<String, DownloadJobStatus> = emptyMap(),
) : RecyclerView.Adapter<ChapterListAdapter.RowHolder>() {

    class RowHolder(val row: LinearLayout) : RecyclerView.ViewHolder(row)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder {
        val context = parent.context
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(Space.MD), context.dp(Space.SM + 2), context.dp(Space.XS + 2), context.dp(Space.SM + 2))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = context.dp(Space.XS + 2) }
        }
        return RowHolder(row)
    }

    override fun onBindViewHolder(holder: RowHolder, position: Int) {
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

        val isLastRead = story.lastReadChapterId == chapter.id
        val radiusPx = context.dp(Space.SM).toFloat()
        val fill = if (isLastRead) ThemeManager.colors.primaryContainer else ThemeManager.colors.elevation1
        row.apply {
            background = ripple(roundedBg(fill, radiusPx), radiusPx, ThemeManager.colors.onSurface)
            isClickable = true
            isFocusable = true
            setOnClickListener { host.showReader(story.id, chapter.id) }
        }
        // Live status from the download queue takes precedence over the static downloaded flag, so
        // an in-flight/queued/failed chapter shows real-time feedback rather than "not downloaded".
        val liveStatus = chapterStatuses[chapter.id]
        val statusLeading: android.view.View = when (liveStatus) {
            DownloadJobStatus.Downloading -> chapterSpinner(context)
            DownloadJobStatus.Pending -> host.dot(ThemeManager.colors.primary)
            DownloadJobStatus.Failed -> host.dot(ThemeManager.colors.error)
            else -> host.chapterStatusDot(chapter.downloaded)
        }
        row.addView(statusLeading.apply {
            (layoutParams as? LinearLayout.LayoutParams)?.setMargins(0, 0, context.dp(Space.SM + 2), 0)
        })
        row.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(
                makeText(
                    context,
                    "${index + 1}. ${sanitizeTitle(chapter.title)}",
                    Type.TITLE_SMALL,
                    if (isLastRead) ThemeManager.colors.primary else ThemeManager.colors.onSurface,
                ).apply {
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    if (isLastRead) setTypeface(typeface, android.graphics.Typeface.BOLD)
                },
            )
            // Subtitle: live queue state first (it's more immediate), then the static offline badge.
            when (liveStatus) {
                DownloadJobStatus.Downloading -> addView(
                    makeText(context, "Downloading…", Type.LABEL_SMALL, ThemeManager.colors.primary).apply {
                        setPadding(0, context.dp(2), 0, 0)
                    },
                )
                DownloadJobStatus.Pending -> addView(
                    makeText(context, "Queued", Type.LABEL_SMALL, ThemeManager.colors.onSurfaceVariant).apply {
                        setPadding(0, context.dp(2), 0, 0)
                    },
                )
                DownloadJobStatus.Failed -> addView(
                    makeText(context, "Download failed", Type.LABEL_SMALL, ThemeManager.colors.error).apply {
                        setPadding(0, context.dp(2), 0, 0)
                    },
                )
                else -> if (chapter.downloaded) {
                    addView(
                        makeText(context, "Available Offline", Type.LABEL_SMALL, ThemeManager.colors.secondary).apply {
                            setPadding(0, context.dp(2), 0, 0)
                        },
                    )
                }
            }
        })
        row.addView(ImageView(context).apply {
            setImageDrawable(context.tintedIcon(R.drawable.wna_more_vert, ThemeManager.colors.onSurfaceVariant))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(context.dp(Space.SM + 2), context.dp(Space.SM + 2), context.dp(Space.SM + 2), context.dp(Space.SM + 2))
            background = selectableRipple(ThemeManager.colors.onSurface)
            isClickable = true
            isFocusable = true
            setOnClickListener { host.showChapterActions(story, chapter, index, list, query, filter) }
            layoutParams = LinearLayout.LayoutParams(context.dp(44), context.dp(44))
        })
    }

    override fun getItemCount(): Int = chapters.size
}

/**
 * Small indeterminate spinner sized to match the chapter status dot (10dp), used in place of the
 * dot for a chapter currently being fetched. Primary-tinted so it reads as "active work".
 */
private fun chapterSpinner(context: Context): android.view.View {
    val t = ThemeManager.current
    val size = context.dp(10)
    return ProgressBar(context).apply {
        indeterminateTintList = ColorStateList.valueOf(t.colors.primary)
        layoutParams = android.widget.FrameLayout.LayoutParams(size, size)
    }
}
