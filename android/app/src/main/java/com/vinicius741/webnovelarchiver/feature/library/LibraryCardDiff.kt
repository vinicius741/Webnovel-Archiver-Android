package com.vinicius741.webnovelarchiver.feature.library

import androidx.recyclerview.widget.DiffUtil
import com.vinicius741.webnovelarchiver.domain.model.PatreonRawStats
import com.vinicius741.webnovelarchiver.domain.model.SourceAvailability
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.story.PublicationStatusPlanning
import com.vinicius741.webnovelarchiver.domain.story.PublicationStatusPlanning.DisplayStatus

/** What the list card's meta row shows ([LibraryBadgeRow]); the row rebuilds its pills only when this changes. */
internal data class LibraryBadgeKey(
    val sourceId: String?,
    val sourceUrl: String,
    val status: DisplayStatus,
    /** null when the badge is hidden (archived stories show none). */
    val availability: SourceAvailability?,
    val score: String?,
    val mayHaveNewChapter: Boolean,
) {
    companion object {
        fun of(
            story: Story,
            now: Long,
        ) = LibraryBadgeKey(
            sourceId = story.sourceId,
            sourceUrl = story.sourceUrl,
            status = PublicationStatusPlanning.displayStatus(story, now),
            availability = story.sourceSyncState.availability.takeUnless { story.isArchived == true },
            score = story.score?.takeIf { it.isNotBlank() },
            mayHaveNewChapter = mayHaveNewChapter(story, now),
        )
    }
}

/**
 * Everything a Library list card or cover tile shows except the chapter coverage (count and capsule), which
 * [patchLibraryProgress] patches in place on download ticks. Equal contents bind to the same pixels, so a
 * story whose only change is its download progress is never rebound.
 */
internal data class LibraryCardContent(
    val title: String,
    val author: String,
    val coverUrl: String?,
    val aiCoverPath: String?,
    val showAiCover: Boolean,
    val badges: LibraryBadgeKey,
    val patreonStats: PatreonRawStats?,
    val tags: List<String>?,
    val hasChapters: Boolean,
) {
    companion object {
        fun of(
            story: Story,
            now: Long,
        ) = LibraryCardContent(
            title = story.title,
            author = story.author,
            coverUrl = story.coverUrl,
            aiCoverPath = story.aiCoverPath,
            showAiCover = story.showAiCover,
            badges = LibraryBadgeKey.of(story, now),
            patreonStats = story.patreonStats,
            tags = story.tags?.take(LIBRARY_TAG_PREVIEW),
            hasChapters = story.totalChapters > 0,
        )
    }
}

/**
 * Card-level changes between two filtered, sorted lists: moves, arrivals and departures by story id, and a
 * change only where [LibraryCardContent] differs. The published library keeps an unchanged story's instance,
 * so only the replaced stories are compared field by field.
 */
internal object LibraryCardDiff {
    fun calculate(
        old: List<Story>,
        new: List<Story>,
        now: Long,
    ): DiffUtil.DiffResult =
        DiffUtil.calculateDiff(
            object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = old.size

                override fun getNewListSize(): Int = new.size

                override fun areItemsTheSame(
                    oldItemPosition: Int,
                    newItemPosition: Int,
                ): Boolean = old[oldItemPosition].id == new[newItemPosition].id

                override fun areContentsTheSame(
                    oldItemPosition: Int,
                    newItemPosition: Int,
                ): Boolean {
                    val before = old[oldItemPosition]
                    val after = new[newItemPosition]
                    return before === after || LibraryCardContent.of(before, now) == LibraryCardContent.of(after, now)
                }
            },
            true,
        )
}
