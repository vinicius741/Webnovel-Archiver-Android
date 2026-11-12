package com.vinicius741.webnovelarchiver.feature.narration

import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.narration.NarrationChapterStatus
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.applyCheckBoxTint
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeProgress
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.selectableRipple
import com.vinicius741.webnovelarchiver.ui.tintedIcon

/** The Chapter audio list's selection, as its rows see it. */
internal interface ChapterRowSelection {
    val active: Boolean

    fun isSelected(chapterId: String): Boolean

    fun toggle(chapterId: String)
}

/** The header, then one row per chapter: title, audio status, a live bar while generating, and a checkbox while selecting. */
internal class ChapterAudioAdapter(
    private val header: LinearLayout,
    private val bookmarkId: String?,
    private val selection: ChapterRowSelection,
    private val onTap: (Chapter, NarrationChapterStatus) -> Unit,
    private val onLongPress: (Chapter) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private var chapters: List<Chapter> = emptyList()
    private val statuses = HashMap<String, NarrationChapterStatus>()

    private class RowHolder(
        val row: LinearLayout,
        val checkbox: CheckBox,
        val title: TextView,
        val status: TextView,
        val bar: ProgressBar,
        val bookmark: ImageView,
    ) : RecyclerView.ViewHolder(row)

    @android.annotation.SuppressLint("NotifyDataSetChanged") // Statuses change across the whole list at once.
    fun submit(
        chapters: List<Chapter>,
        statuses: Map<String, NarrationChapterStatus>,
    ) {
        this.chapters = chapters
        this.statuses.clear()
        this.statuses.putAll(statuses)
        notifyDataSetChanged()
    }

    /** One sentence further: only the generating row changes. */
    fun updateProgress(
        chapterId: String,
        fraction: Float,
    ) {
        if (statuses[chapterId] !is NarrationChapterStatus.Generating) return
        statuses[chapterId] = NarrationChapterStatus.Generating(fraction)
        val index = chapters.indexOfFirst { it.id == chapterId }
        if (index >= 0) notifyItemChanged(index + 1, PROGRESS)
    }

    fun refreshSelection() = notifyItemRangeChanged(1, chapters.size, SELECTION)

    override fun getItemCount(): Int = chapters.size + 1

    override fun getItemViewType(position: Int): Int = if (position == 0) HEADER else ROW

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): RecyclerView.ViewHolder {
        if (viewType == HEADER) {
            (header.parent as? ViewGroup)?.removeView(header)
            header.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            return object : RecyclerView.ViewHolder(header) {}
        }
        val context = parent.context
        val checkbox =
            CheckBox(context).apply {
                applyCheckBoxTint()
                isClickable = false
                isFocusable = false
            }
        val title =
            makeText(context, "", Type.BODY_MEDIUM, ThemeManager.colors.onSurface).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
        val status = makeText(context, "", Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant)
        val bar = makeProgress(context, 0f) as ProgressBar
        val bookmark =
            ImageView(context).apply {
                setImageDrawable(context.tintedIcon(R.drawable.wna_bookmark, ThemeManager.colors.primary))
                contentDescription = "Bookmark"
            }
        val texts =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(title)
                addView(status)
                addView(bar, (bar.layoutParams as LinearLayout.LayoutParams).apply { topMargin = context.dp(Space.XS) })
            }
        val row =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(context.dp(Space.SM), context.dp(Space.SM), context.dp(Space.SM), context.dp(Space.SM))
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(checkbox)
                addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(
                    bookmark,
                    LinearLayout.LayoutParams(context.dp(ICON_DP), context.dp(ICON_DP)).apply {
                        marginStart =
                            context.dp(Space.SM)
                    },
                )
            }
        return RowHolder(row, checkbox, title, status, bar, bookmark)
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>,
    ) {
        if (holder !is RowHolder || payloads.isEmpty()) return onBindViewHolder(holder, position)
        val chapter = chapters[position - 1]
        if (PROGRESS in payloads) bindStatus(holder, statuses[chapter.id] ?: NarrationChapterStatus.None)
        if (SELECTION in payloads) bindSelection(holder, chapter)
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
    ) {
        if (holder !is RowHolder) return
        val chapter = chapters[position - 1]
        holder.title.text = chapter.title
        holder.bookmark.visibility = if (chapter.id == bookmarkId) View.VISIBLE else View.GONE
        bindStatus(holder, statuses[chapter.id] ?: NarrationChapterStatus.None)
        bindSelection(holder, chapter)
        holder.row.setOnClickListener {
            if (selection.active) selection.toggle(chapter.id) else onTap(chapter, statuses[chapter.id] ?: NarrationChapterStatus.None)
        }
        holder.row.setOnLongClickListener {
            onLongPress(chapter)
            true
        }
    }

    private fun bindStatus(
        holder: RowHolder,
        status: NarrationChapterStatus,
    ) {
        holder.status.text = statusLabel(status)
        holder.status.setTextColor(statusColor(status))
        if (status is NarrationChapterStatus.Generating) {
            holder.bar.visibility = View.VISIBLE
            holder.bar.progress = (status.progress.coerceIn(0f, 1f) * PERCENT).toInt()
        } else {
            holder.bar.visibility = View.GONE
        }
    }

    private fun bindSelection(
        holder: RowHolder,
        chapter: Chapter,
    ) {
        val selected = selection.active && selection.isSelected(chapter.id)
        holder.checkbox.visibility = if (selection.active) View.VISIBLE else View.GONE
        holder.checkbox.isChecked = selected
        val radius =
            holder.row.context
                .dp(ThemeManager.shapes.cardRadius)
                .toFloat()
        holder.row.background =
            if (selected) {
                roundedBg(ThemeManager.colors.secondaryContainer, radius)
            } else {
                selectableRipple(ThemeManager.colors.primary and RIPPLE_ALPHA_MASK)
            }
    }

    private companion object {
        const val HEADER = 0
        const val ROW = 1
        const val PERCENT = 100
        const val ICON_DP = 20
        const val RIPPLE_ALPHA_MASK = 0x22FFFFFF
        const val PROGRESS = "progress"
        const val SELECTION = "selection"
    }
}
