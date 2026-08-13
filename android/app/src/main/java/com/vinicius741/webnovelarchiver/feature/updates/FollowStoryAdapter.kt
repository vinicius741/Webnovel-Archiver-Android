package com.vinicius741.webnovelarchiver.feature.updates

import android.view.Gravity
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.vinicius741.webnovelarchiver.domain.model.SourceAvailability
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.applyCheckBoxTint
import com.vinicius741.webnovelarchiver.ui.coverImage
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.size

internal data class FollowStoryItem(
    val story: Story,
    val selected: Boolean,
    val showCover: Boolean,
)

internal class FollowStoryAdapter(
    private val host: ScreenHost,
    private val onToggle: (String, Boolean) -> Unit,
) : RecyclerView.Adapter<FollowStoryAdapter.Holder>() {
    private var items = emptyList<FollowStoryItem>()

    init {
        setHasStableIds(true)
    }

    override fun getItemCount() = items.size

    override fun getItemId(position: Int) =
        items[position]
            .story.id
            .hashCode()
            .toLong()

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): Holder {
        val context = parent.context
        val theme = ThemeManager.current
        val checkbox =
            CheckBox(context).apply {
                applyCheckBoxTint()
                isSaveEnabled = false
            }
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
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                includeFontPadding = false
            }
        val textColumn =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(title)
                addView(subtitle)
            }
        val row =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isSaveEnabled = false
                setPadding(context.dp(Space.MD), context.dp(Space.MD), context.dp(Space.LG), context.dp(Space.MD))
                background = roundedBg(theme.colors.elevation1, context.dp(theme.shapes.cardRadius).toFloat())
                layoutParams =
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = context.dp(Space.MD)
                    }
                addView(checkbox)
                addView(coverSlot)
                addView(
                    textColumn,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = context.dp(Space.MD)
                    },
                )
            }
        val holder = Holder(row, title, subtitle, checkbox, coverSlot)
        row.setOnClickListener { checkbox.isChecked = !checkbox.isChecked }
        return holder
    }

    override fun onBindViewHolder(
        holder: Holder,
        position: Int,
    ) {
        val item = items[position]
        holder.title.text = item.story.title
        holder.subtitle.text =
            buildString {
                append("by ${item.story.author}")
                if (item.story.isArchived == true) append(" · Archived")
                when (item.story.sourceSyncState.availability) {
                    SourceAvailability.available -> Unit
                    SourceAvailability.not_found -> append(" · Source unavailable")
                    SourceAvailability.access_restricted -> append(" · Source access blocked")
                }
            }
        holder.checkbox.setOnCheckedChangeListener(null)
        holder.checkbox.isChecked = item.selected
        holder.checkbox.setOnCheckedChangeListener { _, checked ->
            if (checked == item.selected) return@setOnCheckedChangeListener
            onToggle(item.story.id, checked)
        }
        holder.coverSlot.removeAllViews()
        if (item.showCover) {
            holder.coverSlot.addView(host.coverImage(item.story, 80, 120, false))
        }
    }

    fun submit(
        stories: List<Story>,
        selected: Set<String>,
        showCover: Boolean,
    ) {
        val next = stories.map { FollowStoryItem(it, it.id in selected, showCover) }
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
                    ) = previous[old].story.id == next[new].story.id

                    override fun areContentsTheSame(
                        old: Int,
                        new: Int,
                    ) = previous[old].selected == next[new].selected &&
                        previous[old].showCover == next[new].showCover &&
                        previous[old].story.id == next[new].story.id &&
                        previous[old].story.title == next[new].story.title &&
                        previous[old].story.author == next[new].story.author &&
                        previous[old].story.isArchived == next[new].story.isArchived &&
                        previous[old].story.sourceSyncState.availability == next[new].story.sourceSyncState.availability
                },
            ).dispatchUpdatesTo(this)
    }

    class Holder(
        val row: LinearLayout,
        val title: TextView,
        val subtitle: TextView,
        val checkbox: CheckBox,
        val coverSlot: LinearLayout,
    ) : RecyclerView.ViewHolder(row)
}
