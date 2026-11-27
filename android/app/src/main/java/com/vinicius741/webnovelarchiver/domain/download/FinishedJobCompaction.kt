package com.vinicius741.webnovelarchiver.domain.download

import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import com.vinicius741.webnovelarchiver.domain.model.DownloadJobStatus

/**
 * The stored shape of finished download jobs. `download_queue.json` is rewritten whole on every
 * claim and every finished chapter, and completed jobs stay in it until the story is queued again
 * or the user clears them, so storing each one in full made a long download cost the square of its
 * length in bytes. Nothing reads a completed job's chapter (the Downloads screen, the story banner
 * and notifications only count it, and its place in the list only orders its story), so the
 * completed jobs of one story collapse into one record that keeps its story fields and its chapter
 * indexes as ranges. Reading expands the record back into the same jobs.
 *
 * The record is an ordinary completed [DownloadJob] with a blank chapter, sitting where the story's
 * first collapsed job sat, so the queue stays a plain job list. Every story's first position and
 * the order of all other jobs are unchanged, which is all list order decides. An app that predates
 * this reads the record as one completed job of that story.
 */
object FinishedJobCompaction {
    /** Guards expansion against a damaged range string; no story has this many chapters. */
    private const val MAX_EXPANDED_PER_RECORD = 100_000

    private data class GroupKey(
        val storyId: String,
        val storyTitle: String,
        val sourceId: String,
        val retryCount: Int,
        val maxRetries: Int,
    )

    /** The list to store: each story's collapsible completed jobs replaced by one record. */
    fun compact(jobs: List<DownloadJob>): List<DownloadJob> {
        val groups = LinkedHashMap<GroupKey, MutableList<Int>>()
        jobs.forEachIndexed { position, job -> keyFor(job)?.let { key -> groups.getOrPut(key) { mutableListOf() } += position } }
        val records = HashMap<Int, DownloadJob>()
        val dropped = HashSet<Int>()
        groups.values.forEach { positions ->
            // A repeated index (a duplicate row) keeps its full shape; a lone job gains nothing from a record.
            val members = positions.distinctBy { jobs[it].chapterIndex }
            if (members.size < 2) return@forEach
            records[members.first()] = record(members.map(jobs::get))
            dropped += members.drop(1)
        }
        if (records.isEmpty()) return jobs
        return jobs.indices.mapNotNull { position -> records[position] ?: jobs[position].takeIf { position !in dropped } }
    }

    /** The in-memory queue: every record expanded back into one completed job per chapter index. */
    fun expand(stored: List<DownloadJob>): MutableList<DownloadJob> {
        if (stored.none { it.finishedChapterIndexes != null }) return stored.toMutableList()
        val jobs = ArrayList<DownloadJob>(stored.size)
        stored.forEach { entry ->
            val ranges = entry.finishedChapterIndexes
            val indexes = ranges?.let(::parseIndexes)
            when {
                ranges == null -> jobs += entry
                // Unreadable ranges: keep what an older app would see, one completed job.
                indexes.isNullOrEmpty() -> jobs += entry.copy(chapter = entry.chapter.copy(), finishedChapterIndexes = null)
                else -> indexes.forEach { index -> jobs += expandedJob(entry, index) }
            }
        }
        return jobs
    }

    /** Sorted, distinct indexes as ranges: [0, 1, 2, 5, 7, 8] → "0-2,5,7-8". */
    fun encodeIndexes(indexes: List<Int>): String {
        val sorted = indexes.distinct().sorted()
        val parts = mutableListOf<String>()
        var start = 0
        while (start < sorted.size) {
            var end = start
            while (end + 1 < sorted.size && sorted[end + 1] == sorted[end] + 1) end++
            parts += if (end == start) "${sorted[start]}" else "${sorted[start]}-${sorted[end]}"
            start = end + 1
        }
        return parts.joinToString(",")
    }

    /** Inverse of [encodeIndexes]; null when [ranges] is malformed or implausibly large. */
    fun parseIndexes(ranges: String): List<Int>? {
        val indexes = mutableListOf<Int>()
        ranges.split(',').forEach { part ->
            val bounds = part.trim().split('-')
            val first = bounds.first().toIntOrNull() ?: return null
            val last = if (bounds.size == 2) bounds[1].toIntOrNull() ?: return null else first
            if (bounds.size > 2 || first < 0 || last < first) return null
            if (indexes.size + (last - first + 1) > MAX_EXPANDED_PER_RECORD) return null
            for (index in first..last) indexes += index
        }
        return indexes.distinct().sorted()
    }

    /**
     * Collapsible completed jobs: plain completed rows whose id is the default `<storyId>_<index>`
     * (so expansion recreates it) and whose site is named by [DownloadJob.sourceId] (so nothing
     * needs the chapter URL). Anything else keeps its full shape.
     */
    private fun keyFor(job: DownloadJob): GroupKey? {
        val sourceId = job.sourceId
        val plain =
            job.status == DownloadJobStatus.Completed.wire &&
                job.finishedChapterIndexes == null &&
                sourceId != null &&
                job.chapterIndex >= 0 &&
                job.id == defaultId(job.storyId, job.chapterIndex) &&
                job.error == null &&
                job.errorCategory == null &&
                job.errorCode == null &&
                job.nextRetryAt == null
        if (!plain || sourceId == null) return null
        return GroupKey(job.storyId, job.storyTitle, sourceId, job.retryCount, job.maxRetries)
    }

    private fun record(members: List<DownloadJob>): DownloadJob {
        val first = members.first()
        return DownloadJob(
            id = first.id,
            storyId = first.storyId,
            storyTitle = first.storyTitle,
            sourceId = first.sourceId,
            chapterIndex = first.chapterIndex,
            status = DownloadJobStatus.Completed.wire,
            addedAt = members.minOf { it.addedAt },
            retryCount = first.retryCount,
            maxRetries = first.maxRetries,
            finishedChapterIndexes = encodeIndexes(members.map { it.chapterIndex }),
        )
    }

    private fun expandedJob(
        record: DownloadJob,
        index: Int,
    ): DownloadJob =
        DownloadJob(
            id = defaultId(record.storyId, index),
            storyId = record.storyId,
            storyTitle = record.storyTitle,
            sourceId = record.sourceId,
            chapterIndex = index,
            status = DownloadJobStatus.Completed.wire,
            addedAt = record.addedAt,
            retryCount = record.retryCount,
            maxRetries = record.maxRetries,
        )

    private fun defaultId(
        storyId: String,
        index: Int,
    ) = "${storyId}_$index"
}
