package com.vinicius741.webnovelarchiver.feature.details

import org.junit.Assert.assertEquals
import org.junit.Test

class ChapterSelectionPlanningTest {
    private val chapterIds = listOf("one", "two", "three", "four", "five")

    @Test
    fun selectingRangeIsInclusiveAndPreservesExistingSelection() {
        val result = ChapterSelectionPlanning.applyRange(setOf("five"), chapterIds, 1, 3, selecting = true)

        assertEquals(setOf("two", "three", "four", "five"), result)
    }

    @Test
    fun reverseDragSelectsTheSameRange() {
        val result = ChapterSelectionPlanning.applyRange(emptySet(), chapterIds, 3, 1, selecting = true)

        assertEquals(setOf("two", "three", "four"), result)
    }

    @Test
    fun paintingDeselectionOnlyClearsTheDraggedRange() {
        val result = ChapterSelectionPlanning.applyRange(chapterIds.toSet(), chapterIds, 1, 3, selecting = false)

        assertEquals(setOf("one", "five"), result)
    }
}
