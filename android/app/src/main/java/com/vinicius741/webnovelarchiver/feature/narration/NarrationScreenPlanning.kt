package com.vinicius741.webnovelarchiver.feature.narration

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.narration.NarrationChapterStatus
import com.vinicius741.webnovelarchiver.narration.NarrationJob
import com.vinicius741.webnovelarchiver.narration.NarrationJobState
import com.vinicius741.webnovelarchiver.narration.NarrationPlanning
import com.vinicius741.webnovelarchiver.narration.NarrationProgress
import com.vinicius741.webnovelarchiver.narration.NarrationQueueDocument
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.dropWhile
import java.util.Locale

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
internal object NarrationScreenPlanning {
    /** The one instruction for making chapter audio, wherever the app explains it. */
    const val HOW_TO_GENERATE = "Long-press a chapter in a story, or tap the headphones while reading, to generate its audio."

    /** Chapters a Generate request would queue: no current audio, failed, or audio for older text. */
    fun NarrationChapterStatus?.needsAudio(): Boolean =
        this == null ||
            this is NarrationChapterStatus.None ||
            this is NarrationChapterStatus.Outdated ||
            this is NarrationChapterStatus.Failed

    /** The queue as story groups, in the order each story first appears. */
    fun groups(jobs: List<NarrationJob>): List<QueueGroup> =
        jobs
            .groupBy { it.storyId }
            .map { (storyId, storyJobs) -> QueueGroup(storyId, storyJobs) }

    /**
     * The values of [live] that differ from [shown], what a screen was built from. Comparing with what
     * was shown, rather than skipping the first value, keeps a change that lands between building the
     * screen and subscribing: the Listen screen once kept a finished chapter queued that way, because a
     * published pack rebuilt it while the queue still held the job and the removal came in before the
     * new screen subscribed.
     */
    fun <T> changesSince(
        live: Flow<T>,
        shown: T,
    ): Flow<T> = live.distinctUntilChanged().dropWhile { it == shown }

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

    /** "Generating on Mac · 40%", or "Waiting for Mac · 40%" while the computer holds back for memory or cannot be reached. */
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
