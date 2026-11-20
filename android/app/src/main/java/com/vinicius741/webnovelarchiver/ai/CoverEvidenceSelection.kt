package com.vinicius741.webnovelarchiver.ai

import com.vinicius741.webnovelarchiver.data.storage.ChapterEvidenceStore
import com.vinicius741.webnovelarchiver.domain.model.Story
import timber.log.Timber
import java.util.UUID
import kotlin.math.roundToInt

// Automatic cover evidence: the decision-model scan, scoped to one story phase (CoverPhasePlanning).

/**
 * Decision-model scan, then the story phase the cover depicts (the saved one, else the main phase),
 * then the strongest useful chapters inside it. A phase with too few useful chapters gets a second
 * scan limited to its own chapters before choosing; judgments the first scan cached are free.
 */
internal suspend fun ChapterEvidenceSelector.coverSelection(
    story: Story,
    apiKey: String,
    model: String,
    target: Int,
    evidenceStore: ChapterEvidenceStore,
    onProgress: (String) -> Unit,
): ChapterEvidencePlanning.Selection {
    val operationId = UUID.randomUUID().toString()
    val downloaded =
        story.chapters.indices
            .filter { story.chapters[it].downloaded }
            .map { it + 1 }
    var scan = scan(story, apiKey, model, target, CoverEvidencePlanning, onProgress, operationId)
    var phases = CoverPhasePlanning.phases(CoverPhasePlanning.readings(scan.scored), downloaded)
    var phase = CoverPhasePlanning.resolve(phases, story.aiCoverPhase)
    if (phase != null && phases.size > 1 && CoverPhasePlanning.needsTopUp(scan.scored, phase, target)) {
        val inPhase = phase.chapters
        val judged = scan.scored.map { it.sample.chapter }.toSet()
        val positions = downloaded.indices.filter { downloaded[it] in inPhase }
        // Judged chapters first: they are cached, so their useful ones count toward the target for free
        // and the scan bills only the new chapters it still needs, spread across the phase.
        val (cached, fresh) = positions.partition { downloaded[it] in judged }
        val order = cached + ChapterEvidencePlanning.spreadOrder(fresh.size).map(fresh::get)
        val topUp = scan(story, apiKey, model, target, PhaseScopedRubric(CoverEvidencePlanning, order), onProgress, operationId)
        scan =
            ChapterEvidencePlanning.Scan(
                (scan.scored + topUp.scored).distinctBy { it.sample.chapter }.sortedBy { it.sample.chapter },
                (scan.passages + topUp.passages).distinctBy { it.chapter to it.index }.sortedWith(compareBy({ it.chapter }, { it.index })),
            )
        phases = CoverPhasePlanning.phases(CoverPhasePlanning.readings(scan.scored), downloaded)
        phase = CoverPhasePlanning.resolve(phases, story.aiCoverPhase)
    }
    check(scan.scored.any { it.assessment.useful }) { CoverEvidencePlanning.noEvidenceMessage }
    // One phase (or none) leaves the cover unscoped: a single phase can rest on a few readings.
    val scoped = phase?.takeIf { phases.size > 1 }
    val chosen = CoverPhasePlanning.chooseChapters(scan.scored, scoped, target)
    // Display hints for the picker and the phase row; losing them never fails a finished cover run.
    runCatching {
        evidenceStore.record(story.id, chosen.map { it.sample.chapter - 1 })
        evidenceStore.recordPhases(story.id, phases)
    }.onFailure { Timber.w(it, "Could not persist automatic chapter selection") }
    Timber.d(
        "Cover evidence for %s: phases %s, depicting %s; chapters %s; appearance passages %s",
        story.id,
        phases.map { it.label },
        scoped?.label,
        chosen.map { it.sample.chapter },
        scan.passages.map { "${it.chapter}.${it.index}@${(it.probability * 100).roundToInt()}%" },
    )
    if (scoped == null) return ChapterEvidencePlanning.Selection(read(story, chosen), scan.passages)
    val offStage = CoverPhasePlanning.offStage(scan.scored, scoped)
    val earlier = CoverPhasePlanning.earlierRange(phases, scoped)
    return ChapterEvidencePlanning.Selection(
        chapters = read(story, chosen),
        passages = scan.passages.filter { it.chapter in scoped.chapters && it.chapter !in offStage },
        earlierPassages = earlier?.let { range -> scan.passages.filter { it.chapter in range } }.orEmpty(),
        phase = scoped,
    )
}

/** [base] limited to one story phase's downloaded-chapter positions, judged in [order], with no minimum scan. */
private class PhaseScopedRubric(
    base: EvidenceRubric,
    private val order: List<Int>,
) : EvidenceRubric by base {
    override val minScannedChapters: Int get() = 0

    override fun scanOrder(count: Int): List<Int> = order.filter { it < count }
}
