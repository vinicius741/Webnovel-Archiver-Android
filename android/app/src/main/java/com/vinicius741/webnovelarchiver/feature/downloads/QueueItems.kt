package com.vinicius741.webnovelarchiver.feature.downloads

import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import com.vinicius741.webnovelarchiver.domain.model.DownloadJobStatus
import com.vinicius741.webnovelarchiver.download.DownloadManagerPlanning
import com.vinicius741.webnovelarchiver.download.DownloadPacingSnapshot
import com.vinicius741.webnovelarchiver.download.DownloadPacingUiKind
import com.vinicius741.webnovelarchiver.download.DownloadPacingUiPlanning
import com.vinicius741.webnovelarchiver.download.DownloadQueueSectionPlanning
import com.vinicius741.webnovelarchiver.download.DownloadQueueStatusCopy
import com.vinicius741.webnovelarchiver.download.QueueAction
import com.vinicius741.webnovelarchiver.download.QueueScreenPlan
import com.vinicius741.webnovelarchiver.download.QueueSection
import com.vinicius741.webnovelarchiver.download.QueueStoryPlan
import com.vinicius741.webnovelarchiver.download.SourceCheckCard
import com.vinicius741.webnovelarchiver.source.SourceRegistry

/** Colour role for a status line; the adapter maps it to theme colours at bind time (WARNING is copper). */
internal enum class QueueTone { NORMAL, ACTIVE, WARNING, ERROR, DONE }

/** One row of the Downloads list. Plain data, so DiffUtil only rebinds rows whose text changed. */
internal sealed interface QueueItem {
    val key: String
}

internal data class QueueSummaryItem(
    val summary: QueueSummary,
) : QueueItem {
    override val key = "summary"
}

internal data class QueueCheckItem(
    val card: SourceCheckCard,
    val siteName: String,
) : QueueItem {
    override val key = "check:${card.providerId}"
}

internal data class QueueFailedItem(
    val count: Int,
) : QueueItem {
    override val key = "failed"
}

internal data class QueueHeaderItem(
    val section: QueueSection,
    val title: String,
    val hint: String? = null,
    val collapsible: Boolean = false,
    val expanded: Boolean = true,
) : QueueItem {
    override val key = "header:$section"
}

internal data class QueueStoryItem(
    val storyId: String,
    val title: String,
    val subtitle: String,
    val status: String,
    val tone: QueueTone,
    val completed: Int,
    val total: Int,
    val section: QueueSection,
    val primaryAction: QueueAction,
    val draggable: Boolean,
    val expandable: Boolean,
    val expanded: Boolean,
) : QueueItem {
    override val key = "story:$storyId"
}

internal data class QueueChapterItem(
    val storyId: String,
    val jobId: String,
    val title: String,
    val status: String,
    val tone: QueueTone,
    val jobStatus: String,
    val blocked: Boolean,
    val inlineRetry: Boolean,
    /** The novel's last open chapter row, which closes its card. */
    val last: Boolean = false,
) : QueueItem {
    override val key = "chapter:$jobId"
}

/** In-screen fold state: novels with their chapters open, and whether Finished is open. */
internal const val FINISHED_SECTION_KEY = "section:finished"

internal fun siteName(providerId: String?): String = providerId?.let { SourceRegistry.getById(it)?.name ?: it } ?: "Unknown site"

/**
 * Flattens the screen plan into list rows: summary, attention cards, then each section. Chapter
 * rows are their own items so a countdown tick rebinds one row, not a novel's whole chapter list.
 */
@Suppress("LongParameterList")
internal fun buildQueueItems(
    plan: QueueScreenPlan,
    checkCards: List<SourceCheckCard>,
    queue: List<DownloadJob>,
    pacing: Collection<DownloadPacingSnapshot>,
    nowMillis: Long,
    expandOverride: Map<String, Boolean>,
): List<QueueItem> {
    val waiting = DownloadPacingUiPlanning.waitingJobs(pacing, queue, nowMillis)
    val items = mutableListOf<QueueItem>(QueueSummaryItem(QueueSummaryPlanning.summary(plan)))
    checkCards.forEach { items += QueueCheckItem(it, siteName(it.providerId)) }
    if (plan.failedChapters > 0) items += QueueFailedItem(plan.failedChapters)

    fun addStories(
        stories: List<QueueStoryPlan>,
        draggableSection: Boolean,
    ) {
        stories.forEach { story ->
            val unfinished = story.jobs.filter { it.status != DownloadJobStatus.Completed.wire }
            val expanded = unfinished.isNotEmpty() && expandOverride[story.storyId] == true
            val storyPacing =
                DownloadPacingUiPlanning.storyStatus(
                    storyId = story.storyId,
                    providerName = siteName(story.providerId),
                    storyJobs = story.jobs,
                    snapshots = pacing,
                    nowMillis = nowMillis,
                    allJobs = queue,
                )
            items +=
                QueueStoryItem(
                    storyId = story.storyId,
                    title = story.title,
                    subtitle = "${siteName(story.providerId)} · ${story.counts.completed} of ${story.counts.total} chapters",
                    status = DownloadQueueStatusCopy.storyStatusLine(story, storyPacing),
                    tone = storyTone(story, storyPacing?.kind),
                    completed = story.counts.completed,
                    total = story.counts.total,
                    section = story.section,
                    primaryAction = DownloadManagerPlanning.storyPrimaryAction(story.section, story.counts),
                    draggable = draggableSection && !expanded,
                    expandable = unfinished.isNotEmpty(),
                    expanded = expanded,
                )
            if (expanded) {
                unfinished.forEachIndexed { index, job ->
                    val blocked =
                        story.blocked &&
                            (job.status == DownloadJobStatus.Pending.wire || DownloadQueueSectionPlanning.isBlockedFailure(job))
                    val terminal = job.status == DownloadJobStatus.Failed.wire || job.status == DownloadJobStatus.Cancelled.wire
                    items +=
                        QueueChapterItem(
                            storyId = story.storyId,
                            jobId = job.id,
                            title = "${job.chapterIndex + 1}. ${job.chapter.title}",
                            status =
                                DownloadQueueStatusCopy.chapterStatusLabel(
                                    job,
                                    blocked,
                                    waiting[job.id]?.remainingSeconds,
                                    nowMillis,
                                ),
                            tone = if (blocked) QueueTone.WARNING else chapterTone(job.status),
                            jobStatus = job.status,
                            blocked = blocked,
                            inlineRetry = terminal && !blocked,
                            last = index == unfinished.lastIndex,
                        )
                }
            }
        }
    }

    if (plan.downloading.isNotEmpty()) {
        items += QueueHeaderItem(QueueSection.DOWNLOADING, "Downloading now")
        addStories(plan.downloading, draggableSection = false)
    }
    if (plan.upNext.isNotEmpty()) {
        val reorderable = plan.upNext.size > 1
        items += QueueHeaderItem(QueueSection.UP_NEXT, "Up next", hint = if (reorderable) "Drag to reorder" else null)
        addStories(plan.upNext, draggableSection = reorderable)
    }
    if (plan.paused.isNotEmpty()) {
        items += QueueHeaderItem(QueueSection.PAUSED, "Paused")
        addStories(plan.paused, draggableSection = false)
    }
    if (plan.finished.isNotEmpty()) {
        val expanded = expandOverride[FINISHED_SECTION_KEY] == true
        val failed = plan.finished.sumOf { it.counts.failed }
        val title = "Finished · ${plan.finished.size} ${if (plan.finished.size == 1) "novel" else "novels"}"
        items +=
            QueueHeaderItem(
                QueueSection.FINISHED,
                title,
                hint = if (failed > 0) "$failed failed" else null,
                collapsible = true,
                expanded = expanded,
            )
        if (expanded) addStories(plan.finished, draggableSection = false)
    }
    return items
}

private fun storyTone(
    story: QueueStoryPlan,
    pacing: DownloadPacingUiKind?,
): QueueTone =
    when {
        story.blocked -> QueueTone.WARNING
        pacing == DownloadPacingUiKind.RATE_LIMIT_WAIT || pacing == DownloadPacingUiKind.RETRY_WAIT -> QueueTone.WARNING
        story.section == QueueSection.DOWNLOADING -> QueueTone.ACTIVE
        story.section == QueueSection.FINISHED && (story.counts.hasFailed || story.counts.hasCancelled) -> QueueTone.ERROR
        story.section == QueueSection.FINISHED -> QueueTone.DONE
        else -> QueueTone.NORMAL
    }

private fun chapterTone(status: String): QueueTone =
    when (DownloadJobStatus.parse(status)) {
        DownloadJobStatus.Downloading -> QueueTone.ACTIVE
        DownloadJobStatus.Failed, DownloadJobStatus.Cancelled -> QueueTone.ERROR
        DownloadJobStatus.Completed -> QueueTone.DONE
        DownloadJobStatus.Pending, DownloadJobStatus.Paused -> QueueTone.NORMAL
    }
