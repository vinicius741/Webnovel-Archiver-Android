package com.vinicius741.webnovelarchiver.ai

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.Story
import org.junit.Assert.assertEquals
import org.junit.Test

class AiCoverContextPlanningTest {
    private fun story(manualSelection: MutableList<Int>?) =
        Story(
            chapters =
                mutableListOf(
                    Chapter(id = "a", downloaded = true),
                    Chapter(id = "b", downloaded = false),
                    Chapter(id = "c", downloaded = true),
                ),
            aiCoverContextChapterIndices = manualSelection,
        )

    @Test fun `labels distinguish automatic scans from manual selections`() {
        assertEquals("Automatic (TypeSafe)", AiCoverContextPlanning.contextChaptersLabel(story(null)))
        assertEquals("TypeSafe: 2 chapters", AiCoverContextPlanning.contextChaptersLabel(story(null), listOf(0, 2)))
        // A manual choice always wins; unavailable chapters drop out of its label count.
        assertEquals("Automatic (TypeSafe)", AiCoverContextPlanning.contextChaptersLabel(story(null), emptyList()))
        assertEquals("1 selected", AiCoverContextPlanning.contextChaptersLabel(story(mutableListOf(0, 1, 5)), listOf(0, 2)))
    }

    @Test fun `automatic scope covers downloaded chapters only`() {
        assertEquals(listOf(0, 2), AiCoverContextPlanning.selectContextChapters(story(null)))
        assertEquals(listOf(0), AiCoverContextPlanning.resolveContextChapters(story(mutableListOf(0, 1))))
    }
}
