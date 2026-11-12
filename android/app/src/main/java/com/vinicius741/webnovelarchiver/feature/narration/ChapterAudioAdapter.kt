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

    /** The row's leading and trailing parts that swap with selection mode. */
    private class RowEnds(
        val checkbox: CheckBox,
        val icon: ImageView,
        val bookmark: ImageView,
        val more: ImageView,
    )

    private class RowHolder(
        val row: LinearLayout,
        val ends: RowEnds,
        val title: TextView,
        val status: TextView,
        val bar: ProgressBar,
    ) : RecyclerView.ViewHolder(row) {
        val checkbox get() = ends.checkbox
        val icon get() = ends.icon
        val bookmark get() = ends.bookmark
        val more get() = ends.more
    }

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
        val icon =
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        val title =
            makeText(context, "", Type.TITLE_SMALL, ThemeManager.colors.onSurface).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
        val status = makeText(context, "", Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant)
        val bar = makeProgress(context, 0f) as ProgressBar
        val bookmark =
            ImageView(context).apply {
                setImageDrawable(context.tintedIcon(R.drawable.wna_bookmark, ThemeManager.colors.bookmark))
                contentDescription = "Bookmark"
            }
        val more =
            ImageView(context).apply {
                setImageDrawable(context.tintedIcon(R.drawable.wna_more_vert, ThemeManager.colors.faint))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        val texts =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(title)
                addView(
                    status,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin =
                            context.dp(1)
                    },
                )
                addView(bar, (bar.layoutParams as LinearLayout.LayoutParams).apply { topMargin = context.dp(6) })
            }
        val row =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = context.dp(ROW_MIN_HEIGHT_DP)
                setPadding(context.dp(Space.MD + 2), context.dp(Space.SM), context.dp(Space.MD), context.dp(Space.SM))
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(checkbox)
                addView(
                    icon,
                    LinearLayout.LayoutParams(context.dp(STATUS_ICON_DP), context.dp(STATUS_ICON_DP)).apply {
                        marginEnd =
                            context.dp(Space.MD)
                    },
                )
                addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(
                    bookmark,
                    LinearLayout.LayoutParams(context.dp(ICON_DP), context.dp(ICON_DP)).apply {
                        marginStart =
                            context.dp(Space.SM)
                    },
                )
                addView(
                    more,
                    LinearLayout.LayoutParams(context.dp(ICON_DP), context.dp(ICON_DP)).apply { marginStart = context.dp(Space.SM) },
                )
            }
        return RowHolder(row, RowEnds(checkbox, icon, bookmark, more), title, status, bar)
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
        // A round status badge: play when ready, a waveform while generating, a clock while queued.
        val colors = ThemeManager.colors
        val (res, tint, fill) =
            when (status) {
                is NarrationChapterStatus.Ready -> Triple(R.drawable.wna_play_circle, colors.primary, colors.primaryContainer)
                is NarrationChapterStatus.Generating -> Triple(R.drawable.wna_graphic_eq, colors.secondary, colors.secondaryContainer)
                is NarrationChapterStatus.Queued -> Triple(R.drawable.wna_schedule, colors.onSurfaceVariant, colors.elevation3)
                is NarrationChapterStatus.Failed, is NarrationChapterStatus.Outdated ->
                    Triple(
                        R.drawable.wna_error,
                        colors.error,
                        colors.errorContainer,
                    )
                else -> Triple(R.drawable.wna_radio_unchecked, colors.faint, colors.elevation2)
            }
        holder.icon.setImageDrawable(holder.icon.context.tintedIcon(res, tint))
        holder.icon.background =
            roundedBg(
                fill,
                holder.icon.context
                    .dp(STATUS_ICON_DP / 2)
                    .toFloat(),
            )
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
        holder.icon.visibility = if (selection.active) View.GONE else View.VISIBLE
        holder.more.visibility = if (selection.active) View.GONE else View.VISIBLE
        holder.checkbox.isChecked = selected
        holder.row.background =
            if (selected) {
                android.graphics.drawable.ColorDrawable(ThemeManager.colors.primary and SELECTED_ALPHA_MASK)
            } else {
                selectableRipple(ThemeManager.colors.primary and RIPPLE_ALPHA_MASK)
            }
    }

    private companion object {
        const val HEADER = 0
        const val ROW = 1
        const val PERCENT = 100
        const val ICON_DP = 20
        const val STATUS_ICON_DP = 34
        const val ROW_MIN_HEIGHT_DP = 62
        const val SELECTED_ALPHA_MASK = 0x14FFFFFF
        const val RIPPLE_ALPHA_MASK = 0x22FFFFFF
        const val PROGRESS = "progress"
        const val SELECTION = "selection"
    }
}
