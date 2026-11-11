package com.vinicius741.webnovelarchiver.narration

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import java.security.MessageDigest

/** A request to generate one chapter. */
data class NarrationRequest(
    val storyId: String,
    val chapterId: String,
    val voice: NarrationVoice,
    val origin: NarrationOrigin,
)

/** Pure narration rules: names, fingerprints, queue edits, keep-ahead and estimates. */
@Suppress("TooManyFunctions") // One cohesive set of pure rules, each unit-tested on its own.
object NarrationPlanning {
    const val DEFAULT_KEEP_AHEAD = 5
    val KEEP_AHEAD_CHOICES = listOf(0, 1, 3, 5, 10)

    /** Kokoro at 1x, measured on novel text; replaced by each engine's own measurement. */
    const val DEFAULT_MS_PER_CHAR = 62.0

    /** Kokoro on the owner's phone: about 2.5x real time. Replaced once generation is measured. */
    const val DEFAULT_SPEED = 2.0

    /** 16-bit mono at the 24 kHz Kokoro and most Android voices use. */
    const val BYTES_PER_AUDIO_MS = 48L

    /** Room kept free beyond a chapter's estimated size before generating it. */
    const val FREE_SPACE_MARGIN_BYTES = 200L * 1024L * 1024L

    private const val FOLDER_HEX_CHARS = 32

    /**
     * A folder name for an id: a digest of the whole id, so ids that differ only in punctuation or
     * beyond any length limit never share a folder (the recipe keeps the real id).
     */
    fun folderName(id: String): String = sha256(id).take(FOLDER_HEX_CHARS)

    /** Identifies a chapter's spoken text: equal fingerprints mean the generated audio still fits. */
    fun fingerprint(
        sentences: List<String>,
        chunkingVersion: Int,
    ): String = sha256("$chunkingVersion\n" + sentences.joinToString("\n"))

    /**
     * Applies [requests] to [jobs]. Keep-ahead only adds jobs; a user's request replaces the
     * chapter's existing job (voice, origin, failure). A chapter whose audio already exists is
     * queued only when the user asks to regenerate. [front] puts the requested jobs first, in order.
     */
    fun enqueue(
        jobs: List<NarrationJob>,
        requests: List<NarrationRequest>,
        hasPack: (storyId: String, chapterId: String) -> Boolean,
        front: Boolean,
        now: Long,
        newId: () -> String,
    ): List<NarrationJob> {
        val result = jobs.toMutableList()
        val placed = mutableListOf<NarrationJob>()
        requests.distinctBy { it.storyId to it.chapterId }.forEach { request ->
            val existingIndex = result.indexOfFirst { it.storyId == request.storyId && it.chapterId == request.chapterId }
            val job =
                when {
                    existingIndex >= 0 -> updatedJob(result[existingIndex], request)
                    request.origin != NarrationOrigin.REGENERATE && hasPack(request.storyId, request.chapterId) -> null
                    else -> newJob(request, now, newId())
                } ?: return@forEach
            when {
                front && existingIndex >= 0 -> {
                    result.removeAt(existingIndex)
                    placed += job
                }
                front -> placed += job
                existingIndex >= 0 -> result[existingIndex] = job
                else -> result += job
            }
        }
        return placed + result
    }

    /** Keep-ahead leaves an existing job as it is; a user's request takes it over. */
    private fun updatedJob(
        existing: NarrationJob,
        request: NarrationRequest,
    ): NarrationJob =
        if (request.origin == NarrationOrigin.KEEP_AHEAD) {
            existing
        } else {
            existing.copy(voice = request.voice, origin = request.origin, state = NarrationJobState.QUEUED, error = null)
        }

    private fun newJob(
        request: NarrationRequest,
        now: Long,
        id: String,
    ) = NarrationJob(
        id = id,
        storyId = request.storyId,
        chapterId = request.chapterId,
        voice = request.voice,
        origin = request.origin,
        enqueuedAt = now,
    )

    fun moveToTop(
        jobs: List<NarrationJob>,
        jobId: String,
    ): List<NarrationJob> {
        val job = jobs.firstOrNull { it.id == jobId } ?: return jobs
        return listOf(job) + jobs.filter { it.id != jobId }
    }

    fun retry(
        jobs: List<NarrationJob>,
        jobId: String,
    ): List<NarrationJob> = jobs.map { if (it.id == jobId) it.copy(state = NarrationJobState.QUEUED, error = null) else it }

    fun retryAll(jobs: List<NarrationJob>): List<NarrationJob> = jobs.map { it.copy(state = NarrationJobState.QUEUED, error = null) }

    /** The job the worker should run next: the first one not failed. */
    fun next(jobs: List<NarrationJob>): NarrationJob? = jobs.firstOrNull { it.state == NarrationJobState.QUEUED }

    /**
     * The chapters keep-ahead should queue: within the [count] chapters right after [currentChapterId]
     * (the order auto-advance plays them in), those that can be played and have neither a pack nor a
     * job. The window is fixed before filtering, so refills never reach further ahead than [count].
     */
    fun keepAheadChapters(
        chapters: List<Chapter>,
        currentChapterId: String,
        count: Int,
        isPlayable: (Chapter) -> Boolean,
        hasPack: (chapterId: String) -> Boolean,
        hasJob: (chapterId: String) -> Boolean,
    ): List<String> {
        if (count <= 0) return emptyList()
        val current = chapters.indexOfFirst { it.id == currentChapterId }
        if (current < 0) return emptyList()
        return chapters
            .drop(current + 1)
            .take(count)
            .filter { isPlayable(it) && !hasPack(it.id) && !hasJob(it.id) }
            .map { it.id }
    }

    /** Estimated audio length of [characters] of text at 1x. */
    fun estimatedAudioMs(
        characters: Long,
        msPerChar: Double,
    ): Long = (characters * msPerChar).toLong()

    fun estimatedBytes(audioMs: Long): Long = audioMs * BYTES_PER_AUDIO_MS

    /** Real time to generate [audioMs] of audio at [speed] (audio ms per real ms). */
    fun generationMs(
        audioMs: Long,
        speed: Double,
    ): Long = if (speed <= 0.0) audioMs else (audioMs / speed).toLong()

    /** Blends a new measurement into a running estimate, weighting the past more. */
    fun blend(
        previous: Double?,
        measured: Double,
    ): Double = if (previous == null || previous <= 0.0) measured else previous * BLEND_KEEP + measured * (1 - BLEND_KEEP)

    private const val BLEND_KEEP = 0.7

    fun formatBytes(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return when {
            mb >= 1024 -> "%.1f GB".format(mb / 1024)
            mb >= 10 -> "%.0f MB".format(mb)
            else -> "%.1f MB".format(mb)
        }
    }

    /** "1 h 05 min", "12 min", "<1 min". */
    fun formatDuration(ms: Long): String {
        val minutes = ms / 60_000L
        return when {
            minutes < 1 -> "<1 min"
            minutes < 60 -> "$minutes min"
            else -> "%d h %02d min".format(minutes / 60, minutes % 60)
        }
    }

    private fun sha256(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
