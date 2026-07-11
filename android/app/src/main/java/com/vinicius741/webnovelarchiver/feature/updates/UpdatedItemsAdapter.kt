package com.vinicius741.webnovelarchiver.feature.updates

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.feature.details.showDetails
import com.vinicius741.webnovelarchiver.feature.reader.showReader
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.button
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeCard
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.ripple
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.selectableRipple
import com.vinicius741.webnovelarchiver.ui.tintedIcon
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.launch

/**
 * One list item per novel that has updates: the novel header and its chapter rows live in a single
 * outer card so chapters read as nested under the novel rather than as siblings of it.
 */
private data class UpdatedStoryGroup(
    val story: Story,
    val chapters: List<UpdatedChapter>,
) {
    val stableKey: String get() = "group:${story.id}"
}

internal class UpdatedItemsAdapter(
    private val host: ScreenHost,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private var items = emptyList<UpdatedStoryGroup>()

    init {
        setHasStableIds(true)
    }

    override fun getItemCount() = items.size

    override fun getItemId(position: Int) = items[position].stableKey.hashCode().toLong()

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): RecyclerView.ViewHolder = createGroupHolder(parent)

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
    ) {
        (holder as GroupHolder).bind(items[position])
    }

    fun submit(
        stories: List<Story>,
        chapterIdsByStoryId: Map<String, List<String>>,
    ) {
        val next = buildUpdatedGroups(stories, chapterIdsByStoryId)
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
                    ) = previous[old].stableKey == next[new].stableKey

                    override fun areContentsTheSame(
                        old: Int,
                        new: Int,
                    ) = previous[old] == next[new]
                },
            ).dispatchUpdatesTo(this)
    }

    private fun createGroupHolder(parent: ViewGroup): GroupHolder {
        val context = parent.context
        val title =
            makeText(context, "", Type.TITLE_MEDIUM, ThemeManager.colors.onSurface).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
        val author = makeText(context, "", Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant)
        val labels =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(title)
                addView(author)
            }
        lateinit var open: TextView
        val header =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                // `button` is a ViewGroup DSL that attaches itself; do not addView again.
                open = button("Open", Btn.TEXT) {}
            }
        // Inner stack holds chapter rows; elevated one step above the outer card so they sit
        // visually "inside" the novel group rather than flush with the header surface.
        val chaptersContainer =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = context.dp(Space.MD) }
            }
        val card =
            makeCard(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(header)
                addView(chaptersContainer)
                layoutParams =
                    RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = context.dp(Space.MD) }
            }
        return GroupHolder(card, title, author, open, chaptersContainer)
    }

    private inner class GroupHolder(
        itemView: LinearLayout,
        private val title: TextView,
        private val author: TextView,
        private val open: TextView,
        private val chaptersContainer: LinearLayout,
    ) : RecyclerView.ViewHolder(itemView) {
        fun bind(group: UpdatedStoryGroup) {
            val context = itemView.context
            title.text = group.story.title
            author.text = "by ${group.story.author}"
            open.setOnClickListener { host.showDetails(group.story.id) }
            chaptersContainer.removeAllViews()
            group.chapters.forEachIndexed { i, updated ->
                chaptersContainer.addView(
                    buildChapterRow(context, group.story, updated).apply {
                        if (i > 0) {
                            (layoutParams as LinearLayout.LayoutParams).topMargin = context.dp(Space.XS + 2)
                        }
                    },
                )
            }
        }
    }

    private fun buildChapterRow(
        context: Context,
        story: Story,
        updated: UpdatedChapter,
    ): LinearLayout {
        val radiusPx = context.dp(Space.SM).toFloat()
        val title =
            makeText(context, updated.chapter.title, Type.TITLE_SMALL, ThemeManager.colors.onSurface).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
        val number =
            makeText(context, "Chapter ${updated.index + 1}", Type.LABEL_SMALL, ThemeManager.colors.onSurfaceVariant).apply {
                setPadding(0, context.dp(2), 0, 0)
            }
        val labels =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(title)
                addView(number)
            }
        val bookmarked = story.lastReadChapterId == updated.chapter.id
        val bookmark =
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(
                    context.dp(Space.SM + 2),
                    context.dp(Space.SM + 2),
                    context.dp(Space.SM + 2),
                    context.dp(Space.SM + 2),
                )
                background = selectableRipple(ThemeManager.colors.onSurface)
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(context.dp(44), context.dp(44))
                contentDescription = if (bookmarked) "Clear bookmark" else "Bookmark chapter"
                setImageDrawable(
                    host.app.tintedIcon(
                        if (bookmarked) R.drawable.wna_bookmark else R.drawable.wna_bookmark_outline,
                        if (bookmarked) ThemeManager.colors.primary else ThemeManager.colors.onSurfaceVariant,
                    ),
                )
                setOnClickListener { host.toggleUpdatedChapterBookmark(story.id, updated.chapter.id) }
            }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            // Nested surface: one elevation step above the outer novel card so chapters read as
            // contained content, not peer cards on the page.
            background =
                ripple(
                    roundedBg(ThemeManager.colors.elevation2, radiusPx),
                    radiusPx,
                    ThemeManager.colors.onSurface,
                )
            setPadding(
                context.dp(Space.MD),
                context.dp(Space.SM + 2),
                context.dp(Space.XS + 2),
                context.dp(Space.SM + 2),
            )
            layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            addView(labels)
            addView(bookmark)
            setOnClickListener { host.showReader(story.id, updated.chapter.id) }
        }
    }
}

private fun buildUpdatedGroups(
    stories: List<Story>,
    chapterIdsByStoryId: Map<String, List<String>>,
): List<UpdatedStoryGroup> =
    stories.mapNotNull { story ->
        val chapters = UpdateTrackerPlanning.updatedChapters(story, chapterIdsByStoryId[story.id])
        if (chapters.isEmpty()) null else UpdatedStoryGroup(story, chapters)
    }

private fun ScreenHost.toggleUpdatedChapterBookmark(
    storyId: String,
    chapterId: String,
) {
    scope.launch {
        val updated = repository.toggleBookmark(storyId, chapterId) ?: return@launch
        val chapter = updated.chapters.firstOrNull { it.id == chapterId } ?: return@launch
        toast(if (updated.lastReadChapterId == chapterId) "Bookmarked ${chapter.title}" else "Bookmark cleared")
        showUpdates()
    }
}
