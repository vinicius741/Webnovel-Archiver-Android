package com.vinicius741.webnovelarchiver.download

import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import com.vinicius741.webnovelarchiver.domain.model.DownloadJobStatus

/** Where a novel sits on the Downloads screen; the order is the screen's top-to-bottom order. */
enum class QueueSection { DOWNLOADING, UP_NEXT, PAUSED, FINISHED }

/** One novel's slice of the queue, positioned where the engine will reach it. */
data class QueueStoryPlan(
    val storyId: String,
    val title: String,
    val providerId: String?,
    val section: QueueSection,
    val counts: DownloadCounts,
    /** Chapter jobs in chapter order. */
    val jobs: List<DownloadJob>,
    /** Waiting on a site check: failed as blocked, or pending behind an open verification circuit. */
    val blocked: Boolean,
    /** The novel ahead of this one on the same site, which the engine finishes first. */
    val behindTitle: String? = null,
)

/** A site whose downloads are held until the user passes one interactive Cloudflare check. */
data class BlockedSitePlan(
    val providerId: String,
    val storyIds: List<String>,
    val chapterCount: Int,
    val sampleUrl: String,
)

enum class SourceCheckPhase { NEEDS_CHECK, STILL_BLOCKED, VERIFIED }

/** What the last interactive check for a site produced; the UI holds these in memory only. */
data class SourceCheckOutcome(
    val verified: Boolean,
    val resumedChapters: Int,
    val atMillis: Long,
)

/** One "Needs attention" card for a site. */
data class SourceCheckCard(
    val providerId: String,
    val phase: SourceCheckPhase,
    val storyCount: Int,
    val chapterCount: Int,
    val sampleUrl: String?,
)

data class QueueScreenPlan(
    val counts: DownloadCounts,
    val storyCount: Int,
    val blockedSites: List<BlockedSitePlan>,
    /** Failed chapters the user must retry; blocked chapters resume on their own after the check. */
    val failedChapters: Int,
    val downloading: List<QueueStoryPlan>,
    val upNext: List<QueueStoryPlan>,
    val paused: List<QueueStoryPlan>,
    val finished: List<QueueStoryPlan>,
)

/** Result of resuming a site's held work once its check passes. */
data class VerificationResume(
    val jobs: List<DownloadJob>,
    val resumedChapters: Int,
)

/**
 * Splits the queue into the Downloads screen's sections. The scheduler takes pending jobs
 * first-in-first-out per site, so list position is the download order and each section keeps it.
 */
object DownloadQueueSectionPlanning {
    /** How long the "Verified" card stays after a check passes. */
    const val VERIFIED_CARD_MILLIS = 6_000L

    private const val SOURCE_BLOCKED = "source_blocked"

    fun isBlockedFailure(job: DownloadJob): Boolean = job.status == DownloadJobStatus.Failed.wire && job.errorCategory == SOURCE_BLOCKED

    private fun isPendingLike(job: DownloadJob): Boolean = job.status == DownloadJobStatus.Pending.wire || isBlockedFailure(job)

    fun plan(
        jobs: List<DownloadJob>,
        providerIdForJob: (DownloadJob) -> String?,
        isSourceBlocked: (String) -> Boolean,
    ): QueueScreenPlan {
        val blockedSites = blockedSites(jobs, providerIdForJob, isSourceBlocked)
        val blockedIds = blockedSites.mapTo(HashSet()) { it.providerId }
        val groups = LinkedHashMap<String, MutableList<IndexedValue<DownloadJob>>>()
        jobs.forEachIndexed { index, job -> groups.getOrPut(job.storyId) { mutableListOf() } += IndexedValue(index, job) }
        val ordered =
            groups.values
                .map { indexed -> storyPlan(indexed, providerIdForJob, blockedIds) }
                .sortedBy { it.second }
                .map { it.first }
        val bySection = ordered.groupBy { it.section }
        val running = ordered.filter { it.section == QueueSection.DOWNLOADING || it.section == QueueSection.UP_NEXT }
        val upNext =
            bySection[QueueSection.UP_NEXT].orEmpty().map { story ->
                val ahead = running.subList(0, running.indexOf(story)).lastOrNull { it.providerId == story.providerId }
                story.copy(behindTitle = ahead?.title)
            }
        return QueueScreenPlan(
            counts = DownloadCounts.from(jobs),
            storyCount = groups.size,
            blockedSites = blockedSites,
            failedChapters = jobs.count { it.status == DownloadJobStatus.Failed.wire && !isBlockedFailure(it) },
            downloading = bySection[QueueSection.DOWNLOADING].orEmpty(),
            upNext = upNext,
            paused = bySection[QueueSection.PAUSED].orEmpty(),
            finished = bySection[QueueSection.FINISHED].orEmpty(),
        )
    }

    /** A story plan paired with the queue index that decides its place in its section. */
    private fun storyPlan(
        indexed: List<IndexedValue<DownloadJob>>,
        providerIdForJob: (DownloadJob) -> String?,
        blockedIds: Set<String>,
    ): Pair<QueueStoryPlan, Int> {
        val jobs = indexed.map { it.value }
        val providerId = jobs.firstNotNullOfOrNull(providerIdForJob)
        val section =
            when {
                jobs.any { it.status == DownloadJobStatus.Downloading.wire } -> QueueSection.DOWNLOADING
                jobs.any(::isPendingLike) -> QueueSection.UP_NEXT
                jobs.any { it.status == DownloadJobStatus.Paused.wire } -> QueueSection.PAUSED
                else -> QueueSection.FINISHED
            }
        val orderIndex =
            when (section) {
                QueueSection.DOWNLOADING -> indexed.first { it.value.status == DownloadJobStatus.Downloading.wire }.index
                QueueSection.UP_NEXT -> indexed.first { isPendingLike(it.value) }.index
                else -> indexed.first().index
            }
        val plan =
            QueueStoryPlan(
                storyId = jobs.first().storyId,
                title = jobs.first().storyTitle.ifBlank { "Unknown novel" },
                providerId = providerId,
                section = section,
                counts = DownloadCounts.from(jobs),
                jobs = jobs.sortedBy { it.chapterIndex },
                blocked = providerId in blockedIds && jobs.any(::isPendingLike),
            )
        return plan to orderIndex
    }

    /**
     * Sites needing a check: any chapter failed as blocked, or pending chapters sit behind an open
     * manual-verification circuit (which never fails them, so no failure would announce it).
     */
    fun blockedSites(
        jobs: List<DownloadJob>,
        providerIdForJob: (DownloadJob) -> String?,
        isSourceBlocked: (String) -> Boolean,
    ): List<BlockedSitePlan> {
        val held = LinkedHashMap<String, MutableList<DownloadJob>>()
        jobs.forEach { job ->
            if (!isPendingLike(job)) return@forEach
            val providerId = providerIdForJob(job) ?: return@forEach
            held.getOrPut(providerId) { mutableListOf() } += job
        }
        return held.mapNotNull { (providerId, siteJobs) ->
            val failedBlocked = siteJobs.filter(::isBlockedFailure)
            if (failedBlocked.isEmpty() && !isSourceBlocked(providerId)) return@mapNotNull null
            BlockedSitePlan(
                providerId = providerId,
                storyIds = siteJobs.map { it.storyId }.distinct(),
                chapterCount = siteJobs.size,
                sampleUrl = (failedBlocked.firstOrNull() ?: siteJobs.first()).chapter.url,
            )
        }
    }

    fun checkCards(
        blockedSites: List<BlockedSitePlan>,
        outcomes: Map<String, SourceCheckOutcome>,
        nowMillis: Long,
    ): List<SourceCheckCard> {
        fun recentlyVerified(outcome: SourceCheckOutcome?) =
            outcome?.verified == true && nowMillis - outcome.atMillis < VERIFIED_CARD_MILLIS
        val blockedCards =
            blockedSites.map { site ->
                val outcome = outcomes[site.providerId]
                val phase =
                    when {
                        // The queue can lag the solver by a publish; don't flash "needs a check" in between.
                        recentlyVerified(outcome) -> SourceCheckPhase.VERIFIED
                        outcome?.verified == false -> SourceCheckPhase.STILL_BLOCKED
                        else -> SourceCheckPhase.NEEDS_CHECK
                    }
                SourceCheckCard(site.providerId, phase, site.storyIds.size, site.chapterCount, site.sampleUrl)
            }
        val blockedIds = blockedSites.mapTo(HashSet()) { it.providerId }
        val verifiedCards =
            outcomes
                .filter { (providerId, outcome) -> providerId !in blockedIds && recentlyVerified(outcome) }
                .map { (providerId, outcome) ->
                    SourceCheckCard(providerId, SourceCheckPhase.VERIFIED, 0, outcome.resumedChapters, null)
                }
        return blockedCards + verifiedCards
    }
}
