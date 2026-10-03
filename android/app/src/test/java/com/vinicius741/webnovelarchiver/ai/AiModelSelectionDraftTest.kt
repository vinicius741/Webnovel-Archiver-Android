package com.vinicius741.webnovelarchiver.ai

import com.vinicius741.webnovelarchiver.domain.model.AiSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class AiModelSelectionDraftTest {
    @Test
    fun `cancelable picker edits never mutate saved preferences`() {
        val settings = AiSettings(descriptionModel = "a", reasoningEfforts = mapOf("a" to "low"))
        val draft = AiModelSelectionDraft(settings, "a")
        draft.selectEffort("high")
        draft.selectModel("b")
        draft.selectEffort("minimal")
        assertEquals("a", settings.descriptionModel)
        assertEquals(mapOf("a" to "low"), settings.reasoningEfforts)
        assertEquals(AiModelSelection("b", "minimal"), draft.selection(emptyList()))
    }

    @Test
    fun `browsing models restores each saved or locally edited effort`() {
        val draft = AiModelSelectionDraft(AiSettings(reasoningEfforts = mapOf("a" to "high", "b" to "medium")), "a")
        draft.selectModel("b")
        assertEquals(AiModelSelection("b", "medium"), draft.selection(emptyList()))
        draft.selectEffort("none")
        draft.selectModel("a")
        assertEquals(AiModelSelection("a", "high"), draft.selection(emptyList()))
        draft.selectModel("b")
        assertEquals(AiModelSelection("b", "none"), draft.selection(emptyList()))
    }

    @Test
    fun `confirmation pairs model and supported effort or model default`() {
        val models = listOf(OpenRouterModel("a", "A", "0", "0", reasoning = OpenRouterReasoningOptions(listOf("high"), true)))
        val draft = AiModelSelectionDraft(AiSettings(), "a")
        assertEquals(AiModelSelection("a", "default"), draft.selection(models))
        draft.selectEffort("high")
        assertEquals(AiModelSelection("a", "high"), draft.selection(models))
        draft.selectEffort("none")
        assertEquals(AiModelSelection("a", "default"), draft.selection(models))
    }
}
