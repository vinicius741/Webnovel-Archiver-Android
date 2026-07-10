package com.vinicius741.webnovelarchiver.feature.updates

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
import com.vinicius741.webnovelarchiver.ui.selectableRipple
import com.vinicius741.webnovelarchiver.ui.tintedIcon
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.launch

private sealed interface UpdatedListItem {
    val stableKey: String

    data class StoryHeader(
        val story: Story,
    ) : UpdatedListItem {
        override val stableKey = "story:${story.id}"
    }

    data class ChapterRow(
        val story: Story,
        val index: Int,
        val chapter: Chapter,
    ) : UpdatedListItem {
        override val stableKey = "chapter:${story.id}:${chapter.id}"
    }
}

internal class UpdatedItemsAdapter(
    private val host: ScreenHost,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private var items = emptyList<UpdatedListItem>()

    init {
        setHasStableIds(true)
    }

    override fun getItemCount() = items.size

    override fun getItemId(position: Int) = items[position].stableKey.hashCode().toLong()

    override fun getItemViewType(position: Int) =
        when (items[position]) {
            is UpdatedListItem.StoryHeader -> TYPE_STORY
            is UpdatedListItem.ChapterRow -> TYPE_CHAPTER
        }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): RecyclerView.ViewHolder = if (viewType == TYPE_STORY) createStoryHolder(parent) else createChapterHolder(parent)

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
    ) {
        when (val item = items[position]) {
            is UpdatedListItem.StoryHeader -> (holder as StoryHolder).bind(item.story)
            is UpdatedListItem.ChapterRow -> (holder as ChapterHolder).bind(item)
        }
    }

    fun submit(
        stories: List<Story>,
        chapterIdsByStoryId: Map<String, List<String>>,
    ) {
        val next = buildUpdatedList(stories, chapterIdsByStoryId)
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

    private fun createStoryHolder(parent: ViewGroup): StoryHolder {
        val title =
            makeText(parent.context, "", Type.TITLE_MEDIUM, ThemeManager.colors.onSurface).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
        val author = makeText(parent.context, "", Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant)
        val labels =
            LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                addView(title)
                addView(author)
            }
        lateinit var open: TextView
        val actionSlot =
            LinearLayout(parent.context).apply {
                open = button("Open", Btn.TEXT) {}
            }
        val card =
            makeCard(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(actionSlot)
                layoutParams =
                    RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = parent.context.dp(Space.MD)
                    }
            }
        return StoryHolder(card, title, author, open)
    }

    private fun createChapterHolder(parent: ViewGroup): ChapterHolder {
        val title =
            makeText(parent.context, "", Type.BODY_MEDIUM, ThemeManager.colors.onSurface).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
        val number = makeText(parent.context, "", Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant)
        val labels =
            LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                addView(title)
                addView(number)
            }
        val bookmark =
            ImageView(parent.context).apply {
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(
                    parent.context.dp(Space.SM),
                    parent.context.dp(Space.SM),
                    parent.context.dp(Space.SM),
                    parent.context.dp(Space.SM),
                )
                background = selectableRipple(ThemeManager.colors.onSurface)
                isClickable = true
                isFocusable = true
            }
        val row =
            LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                background = selectableRipple(ThemeManager.colors.onSurface)
                setPadding(
                    parent.context.dp(Space.MD),
                    parent.context.dp(Space.SM),
                    parent.context.dp(Space.SM),
                    parent.context.dp(Space.SM),
                )
                addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(bookmark, LinearLayout.LayoutParams(parent.context.dp(44), parent.context.dp(44)))
            }
        return ChapterHolder(row, title, number, bookmark)
    }

    private inner class StoryHolder(
        itemView: LinearLayout,
        private val title: TextView,
        private val author: TextView,
        private val open: TextView,
    ) : RecyclerView.ViewHolder(itemView) {
        fun bind(story: Story) {
            title.text = story.title
            author.text = "by ${story.author}"
            open.setOnClickListener { host.showDetails(story.id) }
        }
    }

    private inner class ChapterHolder(
        itemView: LinearLayout,
        private val title: TextView,
        private val number: TextView,
        private val bookmark: ImageView,
    ) : RecyclerView.ViewHolder(itemView) {
        fun bind(item: UpdatedListItem.ChapterRow) {
            val bookmarked = item.story.lastReadChapterId == item.chapter.id
            title.text = item.chapter.title
            number.text = "Chapter ${item.index + 1}"
            itemView.setOnClickListener { host.showReader(item.story.id, item.chapter.id) }
            bookmark.contentDescription = if (bookmarked) "Clear bookmark" else "Bookmark chapter"
            bookmark.setImageDrawable(
                host.app.tintedIcon(
                    if (bookmarked) R.drawable.wna_bookmark else R.drawable.wna_bookmark_outline,
                    if (bookmarked) ThemeManager.colors.primary else ThemeManager.colors.onSurfaceVariant,
                ),
            )
            bookmark.setOnClickListener { host.toggleUpdatedChapterBookmark(item.story.id, item.chapter.id) }
        }
    }

    companion object {
        private const val TYPE_STORY = 0
        private const val TYPE_CHAPTER = 1
    }
}

private fun buildUpdatedList(
    stories: List<Story>,
    chapterIdsByStoryId: Map<String, List<String>>,
): List<UpdatedListItem> =
    buildList {
        stories.forEach { story ->
            val chapters = UpdateTrackerPlanning.updatedChapters(story, chapterIdsByStoryId[story.id])
            if (chapters.isNotEmpty()) {
                add(UpdatedListItem.StoryHeader(story))
                chapters.forEach { add(UpdatedListItem.ChapterRow(story, it.index, it.chapter)) }
            }
        }
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
