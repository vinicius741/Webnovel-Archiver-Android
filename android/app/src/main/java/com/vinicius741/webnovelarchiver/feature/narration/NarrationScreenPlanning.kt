package com.vinicius741.webnovelarchiver.feature.narration

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.narration.NarrationChapterStatus
import com.vinicius741.webnovelarchiver.narration.NarrationJob
import com.vinicius741.webnovelarchiver.narration.NarrationJobState
import com.vinicius741.webnovelarchiver.narration.NarrationPlanning
import com.vinicius741.webnovelarchiver.narration.NarrationProgress
import com.vinicius741.webnovelarchiver.narration.NarrationQueueDocument
import java.util.Locale

/** Which chapters the Chapter audio list shows. */
internal enum class ChapterAudioFilter(
    val label: String,
) {
    FROM_BOOKMARK("From bookmark"),
    ALL("All"),
    NOT_GENERATED("Not generated"),
    READY("Ready"),
}

/** What the live generation banner says, whatever story is being generated. */
internal data class GenerationBanner(
    val state: State,
    val headline: String,
    val chapterTitle: String? = null,
    val storyTitle: String? = null,
    val storyId: String? = null,
    val detail: String? = null,
    /** 0..1 for a determinate bar; null for no bar. */
    val fraction: Float? = null,
    val indeterminate: Boolean = false,
) {
    enum class State { GENERATING, STARTING, WAITING, PAUSED, FAILED, IDLE }
}

/** One story's jobs in the generation queue, in queue order. */
internal data class QueueGroup(
    val storyId: String,
    val jobs: List<NarrationJob>,
) {
    val failed: Int get() = jobs.count { it.state == NarrationJobState.FAILED }
}

/** Pure rules behind the narration screens, kept apart from the Views for unit tests. */
@Suppress("TooManyFunctions") // Small pure rules, each unit-tested.
internal object NarrationScreenPlanning {
    /** Index of the bookmarked chapter in [Story.chapters], or -1. */
    fun bookmarkIndex(story: Story): Int = story.lastReadChapterId?.let { id -> story.chapters.indexOfFirst { it.id == id } } ?: -1

    /** Chapters the bookmark filter shows: the bookmarked one and everything after it. */
    fun fromBookmark(
        story: Story,
        chapters: List<Chapter>,
    ): List<Chapter> {
        val bookmark = bookmarkIndex(story)
        if (bookmark < 0) return chapters
        val positions = story.chapters.withIndex().associate { (index, chapter) -> chapter.id to index }
        return chapters.filter { (positions[it.id] ?: -1) >= bookmark }
    }

    /**
     * The filter a story opens with: from the bookmark when there is one, so a long novel starts
     * where the reader is. A chapter the screen was opened for stays visible.
     */
    fun defaultFilter(
        story: Story,
        chapters: List<Chapter>,
        focusId: String?,
    ): ChapterAudioFilter {
        if (bookmarkIndex(story) < 0) return ChapterAudioFilter.ALL
        if (focusId != null && fromBookmark(story, chapters).none { it.id == focusId }) return ChapterAudioFilter.ALL
        return ChapterAudioFilter.FROM_BOOKMARK
    }

    fun filter(
        story: Story,
        chapters: List<Chapter>,
        statuses: Map<String, NarrationChapterStatus>,
        filter: ChapterAudioFilter,
    ): List<Chapter> =
        when (filter) {
            ChapterAudioFilter.ALL -> chapters
            ChapterAudioFilter.FROM_BOOKMARK -> fromBookmark(story, chapters)
            ChapterAudioFilter.NOT_GENERATED -> chapters.filter { statuses[it.id].needsAudio() }
            ChapterAudioFilter.READY -> chapters.filter { statuses[it.id] is NarrationChapterStatus.Ready }
        }

    /** Chapters a Generate request would queue: no current audio, failed, or audio for older text. */
    fun NarrationChapterStatus?.needsAudio(): Boolean =
        this == null ||
            this is NarrationChapterStatus.None ||
            this is NarrationChapterStatus.Outdated ||
            this is NarrationChapterStatus.Failed

    /** Every visible chapter from [anchorId] to [targetId], inclusive and in either direction. */
    fun range(
        visibleIds: List<String>,
        anchorId: String?,
        targetId: String,
    ): Set<String> {
        val to = visibleIds.indexOf(targetId)
        if (to < 0) return emptySet()
        val from = anchorId?.let(visibleIds::indexOf)?.takeIf { it >= 0 } ?: to
        return visibleIds.subList(minOf(from, to), maxOf(from, to) + 1).toSet()
    }

    /** The queue as story groups, in the order each story first appears. */
    fun groups(jobs: List<NarrationJob>): List<QueueGroup> =
        jobs
            .groupBy { it.storyId }
            .map { (storyId, storyJobs) -> QueueGroup(storyId, storyJobs) }

    fun banner(
        progress: NarrationProgress?,
        document: NarrationQueueDocument,
        running: Boolean,
    ): GenerationBanner {
        val queued = document.jobs.count { it.state == NarrationJobState.QUEUED }
        val failed = document.jobs.count { it.state == NarrationJobState.FAILED }
        val job = progress?.job
        return when {
            document.paused ->
                GenerationBanner(
                    GenerationBanner.State.PAUSED,
                    "Generation paused" + (document.pauseReason?.let { " · $it" } ?: ""),
                    detail = if (queued > 0) "${chapters(queued)} waiting" else null,
                )
            job != null && progress.preparing ->
                GenerationBanner(
                    GenerationBanner.State.STARTING,
                    "Starting · loading the voice",
                    chapterTitle = progress.chapterTitle,
                    storyTitle = progress.storyTitle,
                    storyId = job.storyId,
                    detail = if (progress.queuedAfter > 0) "${chapters(progress.queuedAfter)} queued after this" else null,
                    indeterminate = true,
                )
            job != null ->
                GenerationBanner(
                    GenerationBanner.State.GENERATING,
                    generatingTitle(progress),
                    chapterTitle = progress.chapterTitle,
                    storyTitle = progress.storyTitle,
                    storyId = job.storyId,
                    detail = generatingDetail(progress),
                    fraction = progress.fraction,
                )
            queued > 0 && running ->
                GenerationBanner(
                    GenerationBanner.State.STARTING,
                    "Starting…",
                    detail = "${chapters(queued)} queued",
                    indeterminate = true,
                )
            queued > 0 ->
                GenerationBanner(
                    GenerationBanner.State.WAITING,
                    "Waiting to start",
                    detail = "${chapters(queued)} queued. Android has not let generation start yet; it starts when the app is open.",
                )
            failed > 0 ->
                GenerationBanner(GenerationBanner.State.FAILED, "${chapters(failed)} failed", detail = "Retry them from the queue.")
            else -> GenerationBanner(GenerationBanner.State.IDLE, "Nothing is generating")
        }
    }

    /** "Generating on Mac · 40%", or "Waiting for Mac · 40%" while the computer holds back for memory. */
    fun generatingTitle(progress: NarrationProgress): String {
        val where = progress.generatingOn
        val verb =
            if (progress.waitingFor != null &&
                where != null
            ) {
                "Waiting for $where"
            } else {
                "Generating" + (where?.let { " on $it" } ?: "")
            }
        return "$verb · ${percent(progress.fraction)}"
    }

    private fun generatingDetail(progress: NarrationProgress): String =
        buildList {
            progress.waitingFor?.let { add("$it. It resumes by itself") }
            add("${progress.done} of ${progress.total} sentences")
            progress.speed?.let { add("${speed(it)} real time") }
            progress.chapterEtaMs?.let { add("~${NarrationPlanning.formatDuration(it)} left") }
            if (progress.queuedAfter > 0) {
                val total = progress.etaMs?.let { " · ~${NarrationPlanning.formatDuration(it)} for all" }.orEmpty()
                add("${chapters(progress.queuedAfter)} after this$total")
            }
        }.joinToString(" · ")

    fun percent(fraction: Float): String = "${(fraction.coerceIn(0f, 1f) * 100).toInt()}%"

    fun speed(value: Double): String = String.format(Locale.US, "%.1f×", value)

    fun chapters(count: Int): String = "$count ${if (count == 1) "chapter" else "chapters"}"
}
