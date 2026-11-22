package com.vinicius741.webnovelarchiver.feature.cleanup

import com.vinicius741.webnovelarchiver.cleanup.RegexCleanupPresets
import com.vinicius741.webnovelarchiver.domain.model.RegexCleanupRule
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenModelStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CleanupScreenModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val data = FakeCleanupData()

    private fun model(restored: CleanupSavedState? = null) =
        CleanupScreenModel(data, restored, CoroutineScope(SupervisorJob() + dispatcher), previewDispatcher = dispatcher)

    @Test
    fun addingASentenceSavesItFirstAndClearsTheDraftOnceSaved() =
        runTest(dispatcher) {
            val model = model()
            model.setSentenceDraft("   ")
            assertEquals("Sentence cannot be empty", model.addSentence())

            model.setSentenceDraft("  Read at example.com  ")
            assertNull(model.addSentence())
            assertEquals("the draft stays until the save lands", "  Read at example.com  ", model.sentenceDraft)
            runCurrent()

            assertEquals(listOf("Read at example.com"), model.state.value.sentences)
            assertEquals("", model.sentenceDraft)

            model.setSentenceDraft("Read at example.com")
            assertEquals("This sentence is already in the removal list", model.addSentence())
        }

    @Test
    fun sentencesAreEditedAndDeletedByIndex() =
        runTest(dispatcher) {
            data.sentences = listOf("one", "two")
            val model = model()

            assertEquals("This sentence is already in the removal list", model.editSentence(1, "one"))
            assertNull(model.editSentence(1, "second"))
            runCurrent()
            assertEquals(listOf("one", "second"), model.state.value.sentences)

            model.deleteSentence(0)
            runCurrent()
            assertEquals(listOf("second"), model.state.value.sentences)
        }

    @Test
    fun aRegexRuleIsValidatedAddedEditedToggledAndDeleted() =
        runTest(dispatcher) {
            val model = model()
            assertEquals("Rule name is required.", model.saveRegexRule(null, " ", "abc", "i", "both"))

            assertNull(model.saveRegexRule(null, " Separator ", "^-+$", "im", "tts"))
            runCurrent()
            val added = model.state.value.rules.single()
            assertEquals("rule_1", added.id)
            assertEquals("Separator", added.name)
            assertTrue(added.enabled)
            assertEquals("A similar regex rule already exists", model.saveRegexRule(null, "Again", "^-+$", "im", "tts"))

            model.toggleRule(added.id)
            runCurrent()
            assertNull("editing keeps the id and the enabled flag", model.saveRegexRule(model.state.value.rules.single(), "Lines", "^=+$", "m", "both"))
            runCurrent()
            val edited = model.state.value.rules.single()
            assertEquals("rule_1", edited.id)
            assertEquals("Lines", edited.name)
            assertFalse(edited.enabled)

            model.deleteRule(edited.id)
            runCurrent()
            assertTrue(model.state.value.rules.isEmpty())
        }

    @Test
    fun aPresetRuleChecksItsCountAndDuplicates() =
        runTest(dispatcher) {
            val model = model()
            val preset = RegexCleanupPresets.Preset.SEPARATOR_LINES

            assertEquals(PresetSave.BAD_COUNT, model.savePresetRule(preset, 0, "both", null))
            assertEquals(PresetSave.SAVED, model.savePresetRule(preset, 3, "both", null))
            runCurrent()
            assertEquals(1, model.state.value.rules.size)
            assertEquals(PresetSave.DUPLICATE, model.savePresetRule(preset, 3, "both", null))
            assertEquals("re-saving the rule itself is not a duplicate", PresetSave.SAVED, model.savePresetRule(preset, 3, "both", model.state.value.rules.single()))
        }

    @Test
    fun theModeDraftAndScrollRoundTripThroughTheStoresRestoredState() =
        runTest(dispatcher) {
            val model = model()
            model.selectMode(CleanupMode.REGEX)
            model.setSentenceDraft("half-typed")
            model.rememberScroll(CleanupMode.SENTENCES, 120)
            model.rememberScroll(CleanupMode.REGEX, 40)
            assertEquals(CleanupMode.REGEX, model.state.value.mode)

            // A process death: the store keeps the saved state until the screen's model takes it.
            val key = AppRoute.CleanupRules.stableKey
            val store = ScreenModelStore()
            store.restore(key, model.savedState())
            assertEquals(model.savedState(), store.peekRestored<CleanupSavedState>(key))
            val restored = store.get(key) { model(store.takeRestored(key)) }

            assertEquals(CleanupMode.REGEX, restored.state.value.mode)
            assertEquals("half-typed", restored.sentenceDraft)
            assertEquals(120, restored.scrollOf(CleanupMode.SENTENCES))
            assertEquals(40, restored.scrollOf(CleanupMode.REGEX))
            assertNull("only the first model starts from it", store.takeRestored<CleanupSavedState>(key))
        }

    @Test
    fun aNewModelStartsOnSentencesWithAnEmptyDraft() {
        val model = model()
        assertEquals(CleanupMode.SENTENCES, model.state.value.mode)
        assertEquals("", model.sentenceDraft)
        assertEquals(0, model.scrollOf(CleanupMode.REGEX))
    }

    @Test
    fun thePreviewShowsWhatTheRuleLeaves() =
        runTest(dispatcher) {
            assertEquals(" keep", model().previewRule("drop", "", "drop keep"))
        }
}

private class FakeCleanupData : CleanupRulesData {
    var sentences: List<String> = emptyList()
    var rules: List<RegexCleanupRule> = emptyList()
    private var ids = 0

    override fun sentences(): List<String> = sentences

    override fun rules(): List<RegexCleanupRule> = rules

    override suspend fun saveSentences(sentences: List<String>) {
        this.sentences = sentences.toList()
    }

    override suspend fun saveRules(rules: List<RegexCleanupRule>) {
        this.rules = rules.toList()
    }

    override fun newRuleId(): String = "rule_${++ids}"
}
