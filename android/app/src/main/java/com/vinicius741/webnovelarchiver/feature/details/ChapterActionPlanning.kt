package com.vinicius741.webnovelarchiver.feature.details

import com.vinicius741.webnovelarchiver.domain.model.Chapter

/** A chapter's audio as the story page's bulk actions see it. */
internal enum class ChapterAudioState { QUEUED, GENERATING, READY }

/** A chapter's audio state, with the size and length of its generated pack (0 until it is ready). */
internal data class ChapterAudioInfo(
    val state: ChapterAudioState,
    val bytes: Long = 0L,
    val durationMs: Long = 0L,
)

/** What the On this phone card shows about a story's audio. */
internal data class StoryAudioSummary(
    val ready: Int,
    /** Queued or generating. */
    val pending: Int,
    val bytes: Long,
    val durationMs: Long,
    /** Ready chapters before the bookmark. */
    val heard: Int,
)

/**
 * Pure rules behind the story page's bulk chapter actions (the On this phone card, its Text and
 * Audio sheets, and Select's quick picks): which chapters each choice touches, and the per-chapter
 * states the card's bars draw. "Before the bookmark" never includes the bookmarked chapter.
 */
internal object ChapterActionPlanning {
    /** Text bar states. */
    const val TEXT_DOWNLOADED: Byte = 1
    const val TEXT_DOWNLOADING: Byte = 2

    /** Audio bar states. */
    const val AUDIO_READY: Byte = 1
    const val AUDIO_QUEUED: Byte = 2
    const val AUDIO_GENERATING: Byte = 3

    fun textStates(
        chapters: List<Chapter>,
        downloading: Set<String>,
    ): ByteArray =
        ByteArray(chapters.size) { index ->
            val chapter = chapters[index]
            when {
                chapter.downloaded -> TEXT_DOWNLOADED
                chapter.id in downloading -> TEXT_DOWNLOADING
                else -> 0
            }
        }

    fun audioStates(
        chapters: List<Chapter>,
        audio: Map<String, ChapterAudioInfo>,
    ): ByteArray =
        ByteArray(chapters.size) { index ->
            when (audio[chapters[index].id]?.state) {
                ChapterAudioState.READY -> AUDIO_READY
                ChapterAudioState.QUEUED -> AUDIO_QUEUED
                ChapterAudioState.GENERATING -> AUDIO_GENERATING
                null -> 0
            }
        }

    fun summary(
        chapters: List<Chapter>,
        bookmarkIndex: Int,
        audio: Map<String, ChapterAudioInfo>,
    ): StoryAudioSummary {
        var ready = 0
        var pending = 0
        var bytes = 0L
        var duration = 0L
        var heard = 0
        chapters.forEachIndexed { index, chapter ->
            val info = audio[chapter.id] ?: return@forEachIndexed
            if (info.state == ChapterAudioState.READY) {
                ready++
                bytes += info.bytes
                duration += info.durationMs
                if (index < bookmarkIndex) heard++
            } else {
                pending++
            }
        }
        return StoryAudioSummary(ready, pending, bytes, duration, heard)
    }

    /** Chapters before the bookmark with any audio, queued included: what was already heard. */
    fun heard(
        chapters: List<Chapter>,
        bookmarkIndex: Int,
        audio: Map<String, ChapterAudioInfo>,
    ): List<String> {
        if (bookmarkIndex <= 0) return emptyList()
        return chapters.take(bookmarkIndex).map { it.id }.filter { it in audio }
    }

    /**
     * Taking a break: every chapter with audio except the [keep] chapters starting at the bookmark
     * (the bookmarked one included), so listening picks up instantly later. Queued chapters past
     * them leave the queue too.
     */
    fun breakDeletions(
        chapters: List<Chapter>,
        bookmarkIndex: Int,
        keep: Int,
        audio: Map<String, ChapterAudioInfo>,
    ): List<String> {
        val start = bookmarkIndex.coerceAtLeast(0)
        val kept = start until start + keep.coerceAtLeast(0)
        return chapters.mapIndexedNotNull { index, chapter -> chapter.id.takeIf { it in audio && index !in kept } }
    }

    /** Every chapter with audio, generated or queued. */
    fun allAudio(
        chapters: List<Chapter>,
        audio: Map<String, ChapterAudioInfo>,
    ): List<String> = chapters.map { it.id }.filter { it in audio }

    /** Up to [count] chapters from [fromIndex] on that can be narrated and have no audio or job yet. */
    fun nextToGenerate(
        chapters: List<Chapter>,
        fromIndex: Int,
        count: Int,
        audio: Map<String, ChapterAudioInfo>,
        isPlayable: (Chapter) -> Boolean,
    ): List<String> =
        chapters
            .drop(fromIndex.coerceAtLeast(0))
            .asSequence()
            .filter { it.id !in audio && isPlayable(it) }
            .take(count.coerceAtLeast(0))
            .map { it.id }
            .toList()

    /** Indexes of up to [count] chapters from [fromIndex] on that are not downloaded or downloading. */
    fun nextToDownload(
        chapters: List<Chapter>,
        fromIndex: Int,
        count: Int,
        downloading: Set<String> = emptySet(),
    ): List<Int> =
        chapters.indices
            .asSequence()
            .filter { it >= fromIndex.coerceAtLeast(0) && !chapters[it].downloaded && chapters[it].id !in downloading }
            .take(count.coerceAtLeast(0))
            .toList()

    fun bytesOf(
        ids: Collection<String>,
        audio: Map<String, ChapterAudioInfo>,
    ): Long = ids.sumOf { audio[it]?.bytes ?: 0L }

    /** Of [ids], how many are queued or generating rather than generated. */
    fun pendingOf(
        ids: Collection<String>,
        audio: Map<String, ChapterAudioInfo>,
    ): Int = ids.count { id -> audio[id]?.state.let { it != null && it != ChapterAudioState.READY } }

    /** "Ch. 120–149, 153–211", at most three runs then "+2 more"; [indexes] are 0-based. */
    fun rangeLabel(indexes: Collection<Int>): String {
        if (indexes.isEmpty()) return ""
        val sorted = indexes.toSortedSet().toList()
        val runs = mutableListOf<IntRange>()
        var start = sorted.first()
        var previous = start
        for (index in sorted.drop(1)) {
            if (index == previous + 1) {
                previous = index
                continue
            }
            runs += start..previous
            start = index
            previous = index
        }
        runs += start..previous
        val text =
            runs.take(MAX_RUNS).joinToString(", ") {
                if (it.first ==
                    it.last
                ) {
                    "${it.first + 1}"
                } else {
                    "${it.first + 1}–${it.last + 1}"
                }
            }
        return "Ch. $text" + if (runs.size > MAX_RUNS) " +${runs.size - MAX_RUNS} more" else ""
    }

    private const val MAX_RUNS = 3
}
