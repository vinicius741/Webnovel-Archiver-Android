package com.vinicius741.webnovelarchiver.feature.ai

import com.vinicius741.webnovelarchiver.data.storage.AiCoverResultKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiJobChipPlanningTest {
    private val titles = mapOf("a" to "Archmage", "b" to "Blacksmith")

    private fun plan(input: AiJobChipInput) = AiJobChipPlanning.plan(input) { titles[it] }

    @Test
    fun `running cover job wins over results and failures`() {
        val state =
            plan(
                AiJobChipInput(
                    runningCover = "a" to "Painting cover...",
                    unseenCovers = mapOf("b" to AiCoverResultKind.IMAGE),
                    failures =
                        mapOf(AiJobChipPlanning.failureKey("b", AiControlsTab.COVER) to AiJobFailure("b", AiControlsTab.COVER, "boom")),
                ),
            )!!

        assertEquals(AiJobChipStatus.RUNNING, state.status)
        assertEquals("a", state.storyId)
        assertEquals("Painting cover...", state.headline)
        assertEquals("Archmage", state.storyTitle)
        assertNull(state.dismissKey)
    }

    @Test
    fun `hidden on the novel's own AI Controls page`() {
        assertNull(plan(AiJobChipInput(runningCover = "a" to "Painting cover...", viewingStoryId = "a")))
        assertEquals("b", plan(AiJobChipInput(unseenCovers = mapOf("b" to AiCoverResultKind.IMAGE), viewingStoryId = "a"))?.storyId)
    }

    @Test
    fun `unseen result names its kind and can be dismissed`() {
        val image = plan(AiJobChipInput(unseenCovers = mapOf("a" to AiCoverResultKind.IMAGE)))!!
        assertEquals("New cover ready", image.headline)
        assertEquals(AiControlsTab.COVER, image.tab)

        val prompt = plan(AiJobChipInput(unseenCovers = mapOf("a" to AiCoverResultKind.PROMPT)))!!
        assertEquals("Cover prompt ready", prompt.headline)

        assertNull(plan(AiJobChipInput(unseenCovers = mapOf("a" to AiCoverResultKind.IMAGE), dismissed = setOf(image.dismissKey!!))))
    }

    @Test
    fun `newest undismissed failure shows before ready results`() {
        val oldKey = AiJobChipPlanning.failureKey("a", AiControlsTab.COVER)
        val newKey = AiJobChipPlanning.failureKey("b", AiControlsTab.DESCRIPTION)
        val failures =
            linkedMapOf(
                oldKey to AiJobFailure("a", AiControlsTab.COVER, "old"),
                newKey to AiJobFailure("b", AiControlsTab.DESCRIPTION, "new"),
            )

        val state = plan(AiJobChipInput(failures = failures, unseenDescriptions = setOf("a")))!!
        assertEquals(AiJobChipStatus.FAILED, state.status)
        assertEquals("new", state.headline)
        assertEquals(AiControlsTab.DESCRIPTION, state.tab)

        assertEquals("old", plan(AiJobChipInput(failures = failures, dismissed = setOf(newKey)))?.headline)
    }

    @Test
    fun `deleted novels never show`() {
        assertNull(
            plan(AiJobChipInput(runningCover = "gone" to "Painting cover...", unseenCovers = mapOf("gone" to AiCoverResultKind.IMAGE))),
        )
    }

    @Test
    fun `description ready opens the description tab`() {
        val state = plan(AiJobChipInput(unseenDescriptions = setOf("b")))!!
        assertEquals("Description ready", state.headline)
        assertEquals(AiControlsTab.DESCRIPTION, state.tab)
    }
}
