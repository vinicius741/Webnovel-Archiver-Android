package com.vinicius741.webnovelarchiver.feature.updates

import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.vinicius741.webnovelarchiver.domain.model.SourceAvailability
import com.vinicius741.webnovelarchiver.domain.story.FollowReviewEntry
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.coverImage
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeBadge
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.size

internal data class FollowReviewItem(
    val entry: FollowReviewEntry,
    val showCover: Boolean,
)

internal class FollowStoryAdapter(
    private val host: ScreenHost,
    private val onOpen: (String) -> Unit,
) : RecyclerView.Adapter<FollowStoryAdapter.Holder>() {
    private var items = emptyList<FollowReviewItem>()

    init {
        setHasStableIds(true)
    }

    override fun getItemCount() = items.size

    override fun getItemId(position: Int) =
        items[position]
            .entry
            .story
            .id
            .hashCode()
            .toLong()

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): Holder {
        val context = parent.context
        val theme = ThemeManager.current
        val coverSlot = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val title =
            TextView(context).apply {
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, Type.TITLE_SMALL.size())
                typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
                setTextColor(theme.colors.onSurface)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                includeFontPadding = false
            }
        val subtitle =
            TextView(context).apply {
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, Type.BODY_SMALL.size())
                setTextColor(theme.colors.onSurfaceVariant)
                setPadding(0, context.dp(2), 0, 0)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                includeFontPadding = false
            }
        val textColumn =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(title)
                addView(subtitle)
            }
        val badgeSlot = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val row =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                isSaveEnabled = false
                setPadding(context.dp(Space.MD), context.dp(Space.MD), context.dp(Space.LG), context.dp(Space.MD))
                background = roundedBg(theme.colors.elevation1, context.dp(theme.shapes.cardRadius).toFloat())
                layoutParams =
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = context.dp(Space.MD)
                    }
                addView(coverSlot)
                addView(
                    textColumn,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = context.dp(Space.MD)
                    },
                )
                addView(badgeSlot)
            }
        return Holder(row, title, subtitle, badgeSlot, coverSlot)
    }

    override fun onBindViewHolder(
        holder: Holder,
        position: Int,
    ) {
        val item = items[position]
        val entry = item.entry
        val story = entry.story
        val colors = ThemeManager.current.colors
        holder.title.text = story.title
        holder.subtitle.text =
            buildString {
                append("by ${story.author}")
                append(" · ${UpdateTrackerPlanning.reviewDistanceLabel(entry)}")
                when (story.sourceSyncState.availability) {
                    SourceAvailability.available -> Unit
                    SourceAvailability.not_found -> append(" · Source unavailable")
                    SourceAvailability.access_restricted -> append(" · Source access blocked")
                }
            }
        holder.badgeSlot.removeAllViews()
        holder.badgeSlot.addView(
            makeBadge(
                holder.itemView.context,
                UpdateTrackerPlanning.reviewStatusBadgeLabel(entry),
                if (entry.isFollowed) colors.primaryContainer else colors.surfaceVariant,
                if (entry.isFollowed) colors.onPrimaryContainer else colors.onSurfaceVariant,
            ),
        )
        holder.coverSlot.removeAllViews()
        if (item.showCover) {
            holder.coverSlot.addView(host.coverImage(story, 80, 120, false))
        }
        holder.row.setOnClickListener { onOpen(story.id) }
    }

    fun submit(
        entries: List<FollowReviewEntry>,
        showCover: Boolean,
    ) {
        val next = entries.map { FollowReviewItem(it, showCover) }
        val previous = items
        items = next
        DiffUtil
            .calculateDiff(
                object : DiffUtil.Callback() {
                    override fun getOldListSize() = previous.size

                    override fun getNewListSize() = next.size

                    override fun areItemsTheSame(
                        old: Int,
                        new: Int,
                    ) = previous[old].entry.story.id == next[new].entry.story.id

                    override fun areContentsTheSame(
                        old: Int,
                        new: Int,
                    ): Boolean {
                        val prev = previous[old].entry
                        val curr = next[new].entry
                        return previous[old].showCover == next[new].showCover &&
                            prev.isFollowed == curr.isFollowed &&
                            prev.chaptersBehindEnd == curr.chaptersBehindEnd &&
                            prev.story.id == curr.story.id &&
                            prev.story.title == curr.story.title &&
                            prev.story.author == curr.story.author &&
                            prev.story.sourceSyncState.availability == curr.story.sourceSyncState.availability
                    }
                },
            ).dispatchUpdatesTo(this)
    }

    class Holder(
        val row: LinearLayout,
        val title: TextView,
        val subtitle: TextView,
        val badgeSlot: LinearLayout,
        val coverSlot: LinearLayout,
    ) : RecyclerView.ViewHolder(row)
}
