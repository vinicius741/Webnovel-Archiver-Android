package com.vinicius741.webnovelarchiver.ai

import com.vinicius741.webnovelarchiver.domain.model.LifeStage
import com.vinicius741.webnovelarchiver.domain.model.StoryPhase

/**
 * Splits a novel into story phases from the cover scan's per-chapter life-stage readings, so a
 * protagonist who is a child in the opening and an adult for the rest of the book is painted as the
 * adult by default instead of as whoever the introductory chapters describe.
 *
 * Readings are noisy (a flashback the model missed, one odd chapter), so they are smoothed with a
 * three-reading median, and a run that is both short in readings and short in chapters (a flashback
 * arc) is folded into its larger neighbour before phases are drawn. A run with few readings spread
 * across a long stretch is kept: adult chapters state an age far less often than childhood ones.
 * Phase boundaries sit midway between the last reading of one run and the first of the next, and the
 * phases together cover every downloaded chapter.
 */
internal object CoverPhasePlanning {
    /** Probability from which a chapter counts as showing the main character's age. */
    const val MIN_SHOWN = 0.5

    /** Chapters likelier than this to be a dream, flashback or disguise give no reading. */
    const val MAX_TEMPORARY = 0.5

    /** A run with at least this many readings is always its own phase. */
    const val MIN_RUN_READINGS = 3

    /** A run with fewer readings is still its own phase when its readings span this share of the book. */
    const val MIN_RUN_SHARE = 0.1

    data class Reading(
        val chapter: Int,
        val stage: LifeStage,
    )

    /** Confident, non-temporary readings from every judged chapter, in reading order. */
    fun readings(scored: List<ChapterEvidencePlanning.ScoredChapter>): List<Reading> =
        scored
            .mapNotNull { chapter -> confident(chapter)?.let { Reading(chapter.sample.chapter, it) } }
            .distinctBy { it.chapter }
            .sortedBy { it.chapter }

    /**
     * Phases over [downloadedChapters] (1-based chapter numbers). Empty when no chapter showed the
     * main character's age, which leaves the cover unscoped exactly as before phases existed.
     */
    fun phases(
        readings: List<Reading>,
        downloadedChapters: List<Int>,
    ): List<StoryPhase> {
        if (readings.isEmpty() || downloadedChapters.isEmpty()) return emptyList()
        val first = downloadedChapters.min()
        val last = downloadedChapters.max()
        val minSpan = (last - first + 1) * MIN_RUN_SHARE
        val runs = foldShortRuns(runsOf(smooth(readings.sortedBy { it.chapter })), minSpan)
        val starts =
            runs.indices.map { index ->
                if (index == 0) first else (runs[index - 1].last().chapter + runs[index].first().chapter + 1) / 2
            }
        return runs.mapIndexed { index, run ->
            val start = starts[index]
            val end = if (index == runs.lastIndex) last else starts[index + 1] - 1
            StoryPhase(run.first().stage, start, end, downloadedChapters.count { it in start..end })
        }
    }

    /** The phase with the most downloaded chapters; a tie goes to the later one, past the setup. */
    fun mainPhase(phases: List<StoryPhase>): StoryPhase? = largest(phases)

    /**
     * The phase a cover depicts: the largest phase of the saved life stage (a stage key such as
     * "adult", so a rescan that adds or drops a short phase cannot redirect it), else the main phase.
     */
    fun resolve(
        phases: List<StoryPhase>,
        savedStage: String?,
    ): StoryPhase? = largest(phases.filter { it.stage.key == savedStage }) ?: mainPhase(phases)

    /** One choice per life stage, the phase [resolve] would pick for it, in reading order. */
    fun choices(phases: List<StoryPhase>): List<StoryPhase> =
        phases
            .map { it.stage }
            .distinct()
            .mapNotNull { stage -> resolve(phases, stage.key) }
            .sortedBy { it.firstChapter }

    /**
     * The strongest useful chapters inside [phase] (or the whole book when there is none), in reading
     * order, leaving out [offStage] chapters such as a flashback arc. A phase without a useful chapter
     * falls back to its best-scoring judged chapters, so the prompt writer still reads that phase.
     */
    fun chooseChapters(
        scored: List<ChapterEvidencePlanning.ScoredChapter>,
        phase: StoryPhase?,
        target: Int,
    ): List<ChapterEvidencePlanning.ScoredChapter> {
        val excluded = offStage(scored, phase)
        val inPhase = scored.filter { (phase == null || it.sample.chapter in phase.chapters) && it.sample.chapter !in excluded }
        val useful = inPhase.filter { it.assessment.useful }
        return ChapterEvidencePlanning.choose(useful.ifEmpty { inPhase }, target)
    }

    /** Chapters inside [phase] whose own confident reading is another life stage (flashbacks, interludes). */
    fun offStage(
        scored: List<ChapterEvidencePlanning.ScoredChapter>,
        phase: StoryPhase?,
    ): Set<Int> {
        if (phase == null) return emptySet()
        return scored
            .filter { it.sample.chapter in phase.chapters && confident(it)?.let { stage -> stage != phase.stage } == true }
            .map { it.sample.chapter }
            .toSet()
    }

    /**
     * Chapters before [phase] that show a younger version of the same body: the unbroken run of
     * phases just before it, each younger than the one after it. A reincarnation prologue (an adult
     * before a childhood) stops the walk, so a past life's looks never count as the reborn body's.
     */
    fun earlierRange(
        phases: List<StoryPhase>,
        phase: StoryPhase,
    ): IntRange? {
        var index = phases.indexOf(phase)
        if (index <= 0) return null
        var start = phase.firstChapter
        while (index > 0 && phases[index - 1].stage < phases[index].stage) {
            index--
            start = phases[index].firstChapter
        }
        return (start until phase.firstChapter).takeUnless { it.isEmpty() }
    }

    /**
     * A phase needs its own top-up scan when it has fewer useful chapters than it could hold and
     * some of its downloaded chapters have not been judged yet; otherwise another scan finds nothing.
     */
    fun needsTopUp(
        scored: List<ChapterEvidencePlanning.ScoredChapter>,
        phase: StoryPhase,
        target: Int,
    ): Boolean {
        val judged = scored.filter { it.sample.chapter in phase.chapters }
        return judged.size < phase.downloadedChapters && judged.count { it.assessment.useful } < minOf(target, phase.downloadedChapters)
    }
}

private fun confident(chapter: ChapterEvidencePlanning.ScoredChapter): LifeStage? =
    chapter.assessment.stage
        ?.takeIf { it.shown >= CoverPhasePlanning.MIN_SHOWN && it.temporary <= CoverPhasePlanning.MAX_TEMPORARY }
        ?.stage

private fun largest(phases: List<StoryPhase>): StoryPhase? =
    phases.withIndex().maxWithOrNull(compareBy({ it.value.downloadedChapters }, { it.index }))?.value

/** Median of each reading and its two neighbours; the first and last keep their own stage. */
private fun smooth(readings: List<CoverPhasePlanning.Reading>): List<CoverPhasePlanning.Reading> =
    readings.mapIndexed { index, reading ->
        if (index == 0 || index == readings.lastIndex) {
            reading
        } else {
            val median = listOf(readings[index - 1].stage, reading.stage, readings[index + 1].stage).sorted()[1]
            reading.copy(stage = median)
        }
    }

private fun runsOf(readings: List<CoverPhasePlanning.Reading>): List<List<CoverPhasePlanning.Reading>> =
    readings.fold(mutableListOf<MutableList<CoverPhasePlanning.Reading>>()) { runs, reading ->
        if (runs.lastOrNull()?.last()?.stage == reading.stage) runs.last() += reading else runs += mutableListOf(reading)
        runs
    }

private fun isShort(
    run: List<CoverPhasePlanning.Reading>,
    minSpan: Double,
): Boolean = run.size < CoverPhasePlanning.MIN_RUN_READINGS && run.last().chapter - run.first().chapter + 1 < minSpan

/**
 * Folds every short run into the larger of the nearest long runs on either side, judged on the
 * original runs, so two short neighbours cannot merge into a phase neither earned on its own. When
 * every run is short, the shortest is folded into its larger neighbour until one run is left.
 */
private fun foldShortRuns(
    initial: List<List<CoverPhasePlanning.Reading>>,
    minSpan: Double,
): List<List<CoverPhasePlanning.Reading>> {
    val long = initial.indices.filterNot { isShort(initial[it], minSpan) }
    if (long.isNotEmpty()) {
        val relabelled =
            initial.mapIndexed { index, run ->
                if (index in long) return@mapIndexed run
                val target = listOfNotNull(long.lastOrNull { it < index }, long.firstOrNull { it > index }).maxBy { initial[it].size }
                run.map { it.copy(stage = initial[target].first().stage) }
            }
        return runsOf(relabelled.flatten())
    }
    var runs = initial
    while (runs.size > 1) {
        val shortest = runs.indices.filter { isShort(runs[it], minSpan) }.minByOrNull { runs[it].size } ?: break
        val neighbour =
            listOfNotNull(
                (shortest - 1).takeIf { it >= 0 },
                (shortest + 1).takeIf { it <= runs.lastIndex },
            ).maxBy { runs[it].size }
        val stage = runs[neighbour].first().stage
        val relabelled = runs.toMutableList()
        relabelled[shortest] = runs[shortest].map { it.copy(stage = stage) }
        runs = runsOf(relabelled.flatten())
    }
    return runs
}
