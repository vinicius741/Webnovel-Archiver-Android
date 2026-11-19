package com.vinicius741.webnovelarchiver.ai

import com.vinicius741.webnovelarchiver.domain.model.LifeStage
import com.vinicius741.webnovelarchiver.domain.model.LifeStage.ADULT
import com.vinicius741.webnovelarchiver.domain.model.LifeStage.CHILD
import com.vinicius741.webnovelarchiver.domain.model.LifeStage.TEEN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverPhasePlanningTest {
    private val book = (1..300).toList()

    private fun readings(vararg pairs: Pair<Int, LifeStage>) = pairs.map { (chapter, stage) -> CoverPhasePlanning.Reading(chapter, stage) }

    private fun scored(
        chapter: Int,
        stage: LifeStage?,
        shown: Double = 0.9,
        temporary: Double = 0.0,
        useful: Boolean = true,
        utility: Double = 0.5,
    ) = ChapterEvidencePlanning.ScoredChapter(
        ChapterEvidencePlanning.ChapterSample(chapter, "Chapter $chapter", "", ""),
        ChapterEvidencePlanning.Assessment(
            useful = useful,
            utility = utility,
            stage = stage?.let { ChapterEvidencePlanning.StageReading(shown, it, temporary) },
        ),
    )

    private val reincarnation =
        readings(1 to ADULT, 2 to ADULT, 3 to ADULT, 10 to CHILD, 20 to CHILD, 30 to CHILD, 100 to ADULT, 200 to ADULT, 250 to ADULT)

    @Test fun `a childhood opening and an adult story become two phases covering every downloaded chapter`() {
        val phases =
            CoverPhasePlanning.phases(
                readings(1 to CHILD, 3 to CHILD, 5 to CHILD, 8 to CHILD, 40 to ADULT, 80 to ADULT, 150 to ADULT, 290 to ADULT),
                book,
            )
        assertEquals(listOf(CHILD, ADULT), phases.map { it.stage })
        assertEquals(1..23, phases[0].chapters)
        assertEquals(24..300, phases[1].chapters)
        assertEquals(300, phases.sumOf { it.downloadedChapters })
        assertEquals("Adult · Ch 24–300", phases[1].label)
        assertEquals(ADULT, CoverPhasePlanning.mainPhase(phases)?.stage)
    }

    @Test fun `an isolated odd reading is smoothed away and a short flashback run folds into its neighbour`() {
        val noisy =
            readings(10 to ADULT, 20 to ADULT, 30 to TEEN, 40 to ADULT, 50 to ADULT, 60 to CHILD, 61 to CHILD, 80 to ADULT, 90 to ADULT)
        val phases = CoverPhasePlanning.phases(noisy, book)
        assertEquals(listOf(ADULT), phases.map { it.stage })
        assertEquals(1..300, phases.single().chapters)
    }

    @Test fun `two short neighbouring runs fold into the long run instead of into each other`() {
        // Two child readings then two teen readings must not merge into a four-reading teen phase.
        val adult = (5..20).map { it to ADULT }.toTypedArray()
        val phases = CoverPhasePlanning.phases(readings(1 to CHILD, 2 to CHILD, 3 to TEEN, 4 to TEEN, *adult), book)
        assertEquals(listOf(ADULT), phases.map { it.stage })
    }

    @Test fun `a grown-up stretch with few age readings is kept when it spans much of the book`() {
        // Childhood chapters state an age constantly; the adult chapters only twice, far apart.
        val childhood = (1..11).map { it * 3 to CHILD }.toTypedArray()
        val phases = CoverPhasePlanning.phases(readings(*childhood, 150 to ADULT, 290 to ADULT), book)
        assertEquals(listOf(CHILD, ADULT), phases.map { it.stage })
        assertEquals(ADULT, CoverPhasePlanning.mainPhase(phases)?.stage)
    }

    @Test fun `a reincarnation prologue is its own phase and a saved stage picks the largest phase of that stage`() {
        val phases = CoverPhasePlanning.phases(reincarnation, book)
        assertEquals(listOf(ADULT, CHILD, ADULT), phases.map { it.stage })
        val reborn = phases[2]
        assertEquals(reborn, CoverPhasePlanning.mainPhase(phases))
        assertEquals(reborn, CoverPhasePlanning.resolve(phases, "adult"))
        assertEquals(listOf(phases[1], reborn), CoverPhasePlanning.choices(phases))
    }

    @Test fun `earlier passages come only from the younger phases right before the depicted one`() {
        val grownUp =
            CoverPhasePlanning.phases(
                readings(1 to CHILD, 2 to CHILD, 3 to CHILD, 40 to TEEN, 50 to TEEN, 60 to TEEN, 100 to ADULT, 200 to ADULT, 280 to ADULT),
                book,
            )
        assertEquals(1 until grownUp[2].firstChapter, CoverPhasePlanning.earlierRange(grownUp, grownUp[2]))
        assertNull(CoverPhasePlanning.earlierRange(grownUp, grownUp[0]))
        val reborn = CoverPhasePlanning.phases(reincarnation, book)
        // The past life's adult body is never a younger version of the reborn child or adult.
        assertEquals(reborn[1].firstChapter until reborn[2].firstChapter, CoverPhasePlanning.earlierRange(reborn, reborn[2]))
        assertNull(CoverPhasePlanning.earlierRange(reborn, reborn[1]))
    }

    @Test fun `no readings means no phases and an unscoped cover`() {
        assertTrue(CoverPhasePlanning.phases(emptyList(), book).isEmpty())
        assertNull(CoverPhasePlanning.resolve(emptyList(), "adult"))
    }

    @Test fun `the main phase counts downloaded chapters, not the chapter span`() {
        val downloaded = (1..60).toList() + (280..290).toList()
        val phases =
            CoverPhasePlanning.phases(
                readings(5 to CHILD, 20 to CHILD, 50 to CHILD, 281 to ADULT, 285 to ADULT, 289 to ADULT),
                downloaded,
            )
        assertEquals(listOf(60, 11), phases.map { it.downloadedChapters })
        assertEquals(CHILD, CoverPhasePlanning.mainPhase(phases)?.stage)
    }

    @Test fun `resolve keeps a saved stage that still exists and otherwise uses the main phase`() {
        val phases = CoverPhasePlanning.phases(readings(1 to CHILD, 4 to CHILD, 8 to CHILD, 60 to ADULT, 120 to ADULT, 200 to ADULT), book)
        assertEquals(CHILD, CoverPhasePlanning.resolve(phases, "child")?.stage)
        assertEquals(ADULT, CoverPhasePlanning.resolve(phases, "teen")?.stage)
        assertEquals(ADULT, CoverPhasePlanning.resolve(phases, null)?.stage)
    }

    @Test fun `readings skip chapters that do not show the age, temporary scenes and missing answers`() {
        val scan =
            listOf(
                scored(9, ADULT),
                scored(2, CHILD),
                scored(3, ADULT, shown = 0.2),
                scored(4, ADULT, temporary = 0.9),
                scored(5, null),
            )
        assertEquals(readings(2 to CHILD, 9 to ADULT), CoverPhasePlanning.readings(scan))
    }

    @Test fun `chapters are chosen inside the phase, skip flashbacks, and fall back to its best judged chapters`() {
        val phases = CoverPhasePlanning.phases(readings(1 to CHILD, 3 to CHILD, 5 to CHILD, 50 to ADULT, 100 to ADULT, 200 to ADULT), book)
        val adult = phases.last()
        val scan =
            listOf(
                scored(1, CHILD, utility = 0.9),
                scored(2, CHILD, utility = 0.9),
                scored(60, ADULT, utility = 0.4),
                scored(120, ADULT, utility = 0.8),
                scored(125, CHILD, utility = 1.0),
                scored(130, ADULT, useful = false, utility = 0.9),
            )
        assertEquals(setOf(125), CoverPhasePlanning.offStage(scan, adult))
        assertEquals(listOf(60, 120), CoverPhasePlanning.chooseChapters(scan, adult, target = 10).map { it.sample.chapter })
        assertEquals(listOf(120), CoverPhasePlanning.chooseChapters(scan, adult, target = 1).map { it.sample.chapter })
        val noneUseful =
            scan.map {
                if (it.sample.chapter >=
                    60
                ) {
                    scored(it.sample.chapter, it.assessment.stage?.stage, useful = false, utility = it.assessment.utility)
                } else {
                    it
                }
            }
        assertEquals(listOf(120, 130), CoverPhasePlanning.chooseChapters(noneUseful, adult, target = 2).map { it.sample.chapter })
        assertEquals(listOf(1, 2, 60, 120, 125), CoverPhasePlanning.chooseChapters(scan, null, target = 10).map { it.sample.chapter })
    }

    @Test fun `a top-up scan runs only when the phase lacks useful chapters and still has unjudged ones`() {
        val phases =
            CoverPhasePlanning.phases(
                readings(1 to CHILD, 2 to CHILD, 3 to CHILD, 10 to ADULT, 20 to ADULT, 30 to ADULT),
                (1..30).toList(),
            )
        val child = phases.first()
        val adult = phases.last()
        assertEquals(1..6, child.chapters)
        val scan = (1..5).map { scored(it, CHILD) } + listOf(scored(10, ADULT), scored(20, ADULT, useful = false))
        // Childhood has six chapters, five judged: the sixth might still be useful.
        assertTrue(CoverPhasePlanning.needsTopUp(scan, child, target = 10))
        // Every childhood chapter judged: another scan cannot find more, whatever the target.
        assertFalse(CoverPhasePlanning.needsTopUp(scan + scored(6, CHILD, useful = false), child, target = 10))
        assertTrue(CoverPhasePlanning.needsTopUp(scan, adult, target = 3))
        assertFalse(CoverPhasePlanning.needsTopUp(scan, adult, target = 1))
    }
}
