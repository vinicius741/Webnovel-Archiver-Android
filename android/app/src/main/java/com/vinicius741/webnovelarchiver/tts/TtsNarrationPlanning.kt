package com.vinicius741.webnovelarchiver.tts

import com.vinicius741.webnovelarchiver.cleanup.TtsChunkBoundary
import com.vinicius741.webnovelarchiver.cleanup.TtsNarrationChunk
import com.vinicius741.webnovelarchiver.domain.model.TtsSettings
import org.jsoup.nodes.Entities
import java.util.Locale

/** One step of narration: something spoken, or a deliberate pause. */
internal sealed interface TtsTimelineItem {
    /** The reader sentence this item belongs to. A pause belongs to the sentence before it. */
    val chunkIndex: Int

    data class Speech(
        val text: String,
        override val chunkIndex: Int,
        val isTitle: Boolean = false,
    ) : TtsTimelineItem

    data class Silence(
        val durationMs: Long,
        override val chunkIndex: Int,
    ) : TtsTimelineItem
}

/** Turns a chapter's chunks into a paced script: title, sentences, and pauses by boundary type. */
internal object TtsNarrationPlanning {
    const val TITLE_PAUSE_MS = 1_200L
    const val CHAPTER_END_PAUSE_MS = 1_500L

    fun items(
        chunks: List<TtsNarrationChunk>,
        spokenTitle: String? = null,
        settings: TtsSettings = TtsSettings(),
    ): List<TtsTimelineItem> {
        if (chunks.isEmpty()) return emptyList()
        val items = mutableListOf<TtsTimelineItem>()
        if (spokenTitle != null) {
            items += TtsTimelineItem.Speech(spokenTitle, chunkIndex = 0, isTitle = true)
            items += TtsTimelineItem.Silence(TITLE_PAUSE_MS, chunkIndex = 0)
        }
        chunks.forEachIndexed { index, chunk ->
            items += TtsTimelineItem.Speech(chunk.text, chunkIndex = index)
            val pause = if (index == chunks.lastIndex) CHAPTER_END_PAUSE_MS else pauseFor(chunk.boundaryAfter, settings)
            items += TtsTimelineItem.Silence(pause, chunkIndex = index)
        }
        return items
    }

    /** [includeTitle] starts a chapter's first sentence at its spoken title rather than past it. */
    fun itemIndexForChunk(
        items: List<TtsTimelineItem>,
        chunkIndex: Int,
        includeTitle: Boolean = false,
    ): Int {
        if (includeTitle && chunkIndex == 0 && (items.firstOrNull() as? TtsTimelineItem.Speech)?.isTitle == true) return 0
        val index = items.indexOfFirst { it is TtsTimelineItem.Speech && !it.isTitle && it.chunkIndex == chunkIndex }
        return if (index >= 0) index else 0
    }

    /**
     * The place worth saving for a playback point. The title and pauses are not places in the text:
     * the title saves as the first sentence, a pause as the sentence after it.
     */
    fun savePoint(
        items: List<TtsTimelineItem>,
        point: TtsTimelinePoint,
    ): TtsChunkPoint =
        when (val item = items.getOrNull(point.item)) {
            is TtsTimelineItem.Speech -> if (item.isTitle) TtsChunkPoint(0) else TtsChunkPoint(item.chunkIndex, point.offsetMs)
            is TtsTimelineItem.Silence -> {
                val afterTitle = (items.getOrNull(point.item - 1) as? TtsTimelineItem.Speech)?.isTitle == true
                val lastChunk = items.lastOrNull()?.chunkIndex ?: 0
                TtsChunkPoint(if (afterTitle) item.chunkIndex else (item.chunkIndex + 1).coerceAtMost(lastChunk))
            }
            null -> TtsChunkPoint(0)
        }

    /** What to say at a chapter's start; null when its text already opens with the title. */
    fun spokenChapterTitle(
        title: String,
        firstChunk: String?,
    ): String? {
        val spoken = title.replace(Regex("""\s+[-–—:|]+\s+"""), ", ").trim()
        if (spoken.isBlank()) return null
        if (firstChunk != null && comparable(firstChunk).startsWith(comparable(title))) return null
        return spoken
    }

    /** The word start nearest after [offsetMs] into a speech item, for resuming within a sentence. */
    fun charOffsetAt(
        timeline: TtsTimeline,
        item: Int,
        offsetMs: Long,
    ): Int {
        val speech = timeline.items.getOrNull(item) as? TtsTimelineItem.Speech ?: return 0
        val duration = timeline.durationMs(item)
        if (offsetMs <= 0L || duration <= 0L) return 0
        val estimate = (speech.text.length * offsetMs / duration).toInt().coerceIn(0, speech.text.length)
        val space = speech.text.indexOf(' ', estimate)
        return if (space < 0) 0 else space + 1
    }

    /** Typed text as chapter HTML, with blank lines separating paragraphs, for the settings sample. */
    fun sampleHtml(text: String): String =
        text
            .split(BLANK_LINE)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("") { "<p>${Entities.escape(it)}</p>" }

    private fun pauseFor(
        boundary: TtsChunkBoundary,
        settings: TtsSettings,
    ): Long =
        when (boundary) {
            TtsChunkBoundary.SENTENCE -> settings.sentencePauseMs
            TtsChunkBoundary.PARAGRAPH -> settings.paragraphPauseMs
            TtsChunkBoundary.SCENE -> settings.scenePauseMs
        }

    private fun comparable(text: String): String = text.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    private val BLANK_LINE = Regex("""\n\s*\n""")
}

/**
 * Maps audio time onto a chapter's items at 1x. Durations start as estimates from text length and
 * become exact as each item is rendered, so the chapter's length firms up during playback.
 */
internal class TtsTimeline(
    items: List<TtsTimelineItem>,
    phrasePauseMs: Long = 0L,
) {
    @Volatile
    var items: List<TtsTimelineItem> = items
        private set

    /** Extra real time held at each gap inside a sentence; rendered audio only. */
    @Volatile
    var phrasePauseMs: Long = phrasePauseMs
        private set
    private val knownMs = arrayOfNulls<Long>(items.size)
    private var msPerChar = DEFAULT_MS_PER_CHAR
    private var knownChars = 0L
    private var knownSpeechMs = 0L
    private var starts: LongArray? = null

    val isExact: Boolean
        @Synchronized get() = items.indices.all { items[it] is TtsTimelineItem.Silence || knownMs[it] != null }

    /** The same script with new pause lengths; what is already rendered stays valid. */
    @Synchronized
    fun updatePacing(
        paced: List<TtsTimelineItem>,
        phrasePauseMs: Long,
    ) {
        require(paced.size == items.size) { "Pacing cannot change the script" }
        items = paced
        this.phrasePauseMs = phrasePauseMs
        starts = null
    }

    @Synchronized
    fun setKnownDuration(
        item: Int,
        durationMs: Long,
    ) {
        if (item !in items.indices || knownMs[item] != null) return
        knownMs[item] = durationMs
        (items[item] as? TtsTimelineItem.Speech)?.let { speech ->
            knownChars += speech.text.length
            knownSpeechMs += durationMs
            if (knownChars >= MIN_CALIBRATION_CHARS) msPerChar = knownSpeechMs.toDouble() / knownChars
        }
        starts = null
    }

    @Synchronized
    fun durationMs(item: Int): Long =
        knownMs.getOrNull(item) ?: when (val entry = items.getOrNull(item)) {
            is TtsTimelineItem.Silence -> entry.durationMs
            is TtsTimelineItem.Speech -> (entry.text.length * msPerChar).toLong()
            null -> 0L
        }

    @Synchronized
    fun totalMs(): Long = starts().last()

    @Synchronized
    fun positionMs(
        item: Int,
        offsetMs: Long,
    ): Long {
        val all = starts()
        return (all[item.coerceIn(0, all.lastIndex)] + offsetMs.coerceAtLeast(0L)).coerceAtMost(all.last())
    }

    /** The item and offset at [positionMs]; a time past the end lands at the end of the last item. */
    @Synchronized
    fun locate(positionMs: Long): TtsTimelinePoint {
        if (items.isEmpty()) return TtsTimelinePoint(0, 0L)
        val all = starts()
        val clamped = positionMs.coerceIn(0L, all.last())
        var low = 0
        var high = items.lastIndex
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (all[middle] <= clamped) low = middle else high = middle - 1
        }
        return TtsTimelinePoint(low, clamped - all[low])
    }

    private fun starts(): LongArray =
        starts ?: LongArray(items.size + 1).also { built ->
            items.indices.forEach { built[it + 1] = built[it] + durationMs(it) }
            starts = built
        }

    private companion object {
        /** Measured for a Google voice at 1x; replaced by this chapter's own rate once rendered. */
        const val DEFAULT_MS_PER_CHAR = 57.0
        const val MIN_CALIBRATION_CHARS = 200L
    }
}

internal data class TtsTimelinePoint(
    val item: Int,
    val offsetMs: Long,
)
