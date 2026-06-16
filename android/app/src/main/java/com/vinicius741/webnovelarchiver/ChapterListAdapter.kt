package com.vinicius741.webnovelarchiver

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import com.vinicius741.webnovelarchiver.core.Chapter
import com.vinicius741.webnovelarchiver.core.Story
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.chapterStatusDot
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
        row.addView(host.chapterStatusDot(chapter.downloaded).apply {
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
            if (chapter.downloaded) {
                addView(
                    makeText(context, "Available Offline", Type.LABEL_SMALL, ThemeManager.colors.secondary).apply {
                        setPadding(0, context.dp(2), 0, 0)
                    },
                )
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
