package com.vinicius741.webnovelarchiver.feature.updates

import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.navigation.UpdateFollowSelectionState
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.applyCheckBoxTint
import com.vinicius741.webnovelarchiver.ui.button
import com.vinicius741.webnovelarchiver.ui.coverImage
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeEmptyState
import com.vinicius741.webnovelarchiver.ui.makeSearchField
import com.vinicius741.webnovelarchiver.ui.makeSelectableCardRow
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.row
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.size
import com.vinicius741.webnovelarchiver.ui.verticalFill
import kotlinx.coroutines.launch

internal fun ScreenHost.showUpdateFollowSelection() {
    activeStory = null
    rerender = { showUpdateFollowSelection() }
    // Archives are read-only; exclude them from the picker so users don't see duplicate titles.
    val stories = UpdateTrackerPlanning.followableStories(repository.library()).sortedBy { it.title.lowercase() }
    val selected =
        repository.getUpdateFollowedStoryIds().toMutableSet().apply {
            retainAll(stories.mapTo(hashSetOf()) { it.id })
        }
    scope.launch { repository.saveUpdateFollowedStoryIds(selected.toList()) }
    val state = updateFollowSelectionState
    lateinit var adapter: FollowStoryAdapter

    screen(
        route = AppRoute.UpdateFollowSelection,
        title = "Follow Updates",
        subtitle = "${selected.size} selected",
        onBack = { showUpdates() },
    ) {
        if (stories.isEmpty()) {
            addView(
                makeEmptyState(
                    context,
                    title = "Your library is empty",
                    message = "Import stories before setting up update tracking.",
                    iconRes = R.drawable.wna_menu_book,
                ),
            )
            return@screen
        }
        row {
            button("Select All", Btn.TONAL) {
                selected.addAll(UpdateTrackerPlanning.filterStories(stories, state.query).map { it.id })
                scope.launch { repository.saveUpdateFollowedStoryIds(selected.toList()) }
                adapter.submit(stories, selected, state.query, state.showCovers)
            }
            button("Clear", Btn.OUTLINED) {
                selected.removeAll(UpdateTrackerPlanning.filterStories(stories, state.query).map { it.id }.toSet())
                scope.launch { repository.saveUpdateFollowedStoryIds(selected.toList()) }
                adapter.submit(stories, selected, state.query, state.showCovers)
            }
        }
        val search =
            makeSearchField(context, "Search novels").apply {
                setText(state.query)
                addTextChangedListener(
                    object : TextWatcher {
                        override fun beforeTextChanged(
                            s: CharSequence?,
                            start: Int,
                            count: Int,
                            after: Int,
                        ) = Unit

                        override fun afterTextChanged(s: Editable?) = Unit

                        override fun onTextChanged(
                            s: CharSequence?,
                            start: Int,
                            before: Int,
                            count: Int,
                        ) {
                            state.query = s?.toString().orEmpty()
                            adapter.submit(stories, selected, state.query, state.showCovers)
                        }
                    },
                )
            }
        addView(search)
        addView(
            makeShowCoversToggleRow(state) {
                adapter.submit(stories, selected, state.query, state.showCovers)
            },
        )
        adapter =
            FollowStoryAdapter(this@showUpdateFollowSelection) { storyId, checked ->
                if (checked) selected.add(storyId) else selected.remove(storyId)
                scope.launch { repository.saveUpdateFollowedStoryIds(selected.toList()) }
            }
        val list =
            RecyclerView(context).apply {
                layoutManager = LinearLayoutManager(context)
                this.adapter = adapter
                itemAnimator = null
            }
        addView(list, verticalFill())
        adapter.submit(stories, selected, state.query, state.showCovers)
    }
}

private data class FollowStoryItem(
    val story: Story,
    val selected: Boolean,
    val showCover: Boolean,
)

private class FollowStoryAdapter(
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
    ) = Holder(LinearLayout(parent.context))

    override fun onBindViewHolder(
        holder: Holder,
        position: Int,
    ) {
        val item = items[position]
        val row = host.makeFollowRow(item.story, item.selected, item.showCover) { onToggle(item.story.id, it) }
        holder.container.removeAllViews()
        holder.container.addView(row)
    }

    fun submit(
        stories: List<Story>,
        selected: Set<String>,
        query: String,
        showCover: Boolean,
    ) {
        val next = UpdateTrackerPlanning.filterStories(stories, query).map { FollowStoryItem(it, it.id in selected, showCover) }
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
                    ) = previous[old] == next[new]
                },
            ).dispatchUpdatesTo(this)
    }

    class Holder(
        val container: LinearLayout,
    ) : RecyclerView.ViewHolder(container)
}

private fun ScreenHost.makeShowCoversToggleRow(
    state: UpdateFollowSelectionState,
    onChanged: () -> Unit,
): LinearLayout {
    val theme = ThemeManager.current
    val checkBox =
        CheckBox(app).apply {
            isChecked = state.showCovers
            applyCheckBoxTint()
            setOnCheckedChangeListener { _, checked ->
                state.showCovers = checked
                scope.launch { repository.saveDisplayPreferences(repository.getDisplayPreferences().copy(showCoversOnUpdates = checked)) }
                onChanged()
            }
        }
    val labels =
        LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                TextView(app).apply {
                    text = "Show covers"
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, Type.TITLE_SMALL.size())
                    setTextColor(theme.colors.onSurface)
                },
            )
            addView(
                TextView(app).apply {
                    text = "Display each novel's cover thumbnail"
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, Type.BODY_SMALL.size())
                    setTextColor(theme.colors.onSurfaceVariant)
                },
            )
        }
    return LinearLayout(app).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(Space.MD), dp(Space.MD), dp(Space.LG), dp(Space.MD))
        background = roundedBg(theme.colors.elevation1, dp(theme.shapes.cardRadius).toFloat())
        addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(checkBox)
        isClickable = true
        isFocusable = true
        setOnClickListener { checkBox.isChecked = !checkBox.isChecked }
    }
}

private fun ScreenHost.makeFollowRow(
    story: Story,
    selected: Boolean,
    showCover: Boolean,
    toggle: (Boolean) -> Unit,
): LinearLayout =
    makeSelectableCardRow(
        app,
        story.title,
        buildString {
            append("by ${story.author}")
            if (story.isArchived == true) append(" · Archived")
        },
        selected,
        toggle,
    ).apply {
        if (showCover) addView(coverImage(story, 80, 120, false), 0)
    }
