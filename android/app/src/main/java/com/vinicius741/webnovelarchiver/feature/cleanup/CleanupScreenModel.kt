package com.vinicius741.webnovelarchiver.feature.cleanup

import com.vinicius741.webnovelarchiver.cleanup.RegexCleanupPresets
import com.vinicius741.webnovelarchiver.cleanup.RegexRuleCleanup
import com.vinicius741.webnovelarchiver.cleanup.SentenceRemovalPlanning
import com.vinicius741.webnovelarchiver.domain.model.RegexCleanupRule
import com.vinicius741.webnovelarchiver.navigation.ScreenModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class CleanupMode(
    val label: String,
) {
    SENTENCES("Full sentences"),
    REGEX("Rules"),
}

/** The cleanup lists the screen edits: the removed sentences and the regex rules. */
internal interface CleanupRulesData {
    fun sentences(): List<String>

    fun rules(): List<RegexCleanupRule>

    suspend fun saveSentences(sentences: List<String>)

    suspend fun saveRules(rules: List<RegexCleanupRule>)

    /** An id for a new rule. */
    fun newRuleId(): String
}

/** The Cleanup screen's view memory kept across a process death (see [CleanupScreenStatePersistence]). */
internal data class CleanupSavedState(
    val mode: CleanupMode = CleanupMode.SENTENCES,
    val sentenceDraft: String = "",
    /** Each mode's own scroll offset. */
    val scroll: Map<CleanupMode, Int> = emptyMap(),
)

internal data class CleanupUiState(
    val mode: CleanupMode,
    val sentences: List<String>,
    val rules: List<RegexCleanupRule>,
)

/** How saving a preset rule went: saved, a count out of range, or a rule like it already there. */
internal enum class PresetSave { SAVED, BAD_COUNT, DUPLICATE }

/**
 * Text cleanup (`AppRoute.CleanupRules`): the open mode (sentences or regex rules), both lists, the
 * sentence draft, and each mode's scroll offset. The draft and scroll offsets are not part of
 * [state], so typing and scrolling never rebuild the page; they are read at build time and saved
 * across a process death ([savedState]). Saves run to the end even if the screen closes meanwhile.
 */
internal class CleanupScreenModel(
    private val data: CleanupRulesData,
    restored: CleanupSavedState? = null,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val previewDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ScreenModel(scope) {
    private var mode = restored?.mode ?: CleanupMode.SENTENCES

    /** The sentence being typed, kept across renders. */
    var sentenceDraft: String = restored?.sentenceDraft.orEmpty()
        private set
    private val scroll = restored?.scroll.orEmpty().toMutableMap()
    private val mutableState = MutableStateFlow(compute())

    val state: StateFlow<CleanupUiState> = mutableState.asStateFlow()

    /** Re-reads both lists (another screen or a restore may have changed them) and returns the state. */
    fun refresh(): CleanupUiState = compute().also { mutableState.value = it }

    fun selectMode(next: CleanupMode) {
        mode = next
        refresh()
    }

    fun setSentenceDraft(text: String) {
        sentenceDraft = text
    }

    fun scrollOf(mode: CleanupMode): Int = scroll[mode] ?: 0

    fun rememberScroll(
        mode: CleanupMode,
        y: Int,
    ) {
        scroll[mode] = y
    }

    fun savedState(): CleanupSavedState = CleanupSavedState(mode, sentenceDraft, scroll.toMap())

    /** Adds the draft to the list; returns why it cannot be added, else null (the draft clears once saved). */
    fun addSentence(): String? {
        val result = SentenceRemovalPlanning.save(data.sentences(), sentenceDraft)
        if (!result.valid) return result.error ?: "Invalid sentence"
        save {
            data.saveSentences(result.sentences)
            sentenceDraft = ""
        }
        return null
    }

    /** Replaces the sentence at [index]; returns why it cannot be saved, else null. */
    fun editSentence(
        index: Int,
        text: String,
    ): String? {
        val result = SentenceRemovalPlanning.save(data.sentences(), text, index)
        if (!result.valid) return result.error ?: "Invalid sentence"
        save { data.saveSentences(result.sentences) }
        return null
    }

    fun deleteSentence(index: Int) {
        save { data.saveSentences(SentenceRemovalPlanning.delete(data.sentences(), index)) }
    }

    /** Adds or replaces (by [existing]'s id) a hand-written rule; returns why it cannot be saved, else null. */
    fun saveRegexRule(
        existing: RegexCleanupRule?,
        name: String,
        pattern: String,
        flags: String,
        appliesTo: String,
    ): String? {
        val validation = RegexRuleCleanup.validateRegexRule(name, pattern, flags)
        if (!validation.valid) return validation.error ?: "Invalid regex rule"
        val normalizedPattern = validation.normalizedPattern.orEmpty()
        val normalizedFlags = validation.normalizedFlags.orEmpty()
        val rules = data.rules()
        if (RegexRuleCleanup.hasSimilarRegexRule(rules, existing?.id, normalizedPattern, normalizedFlags, appliesTo)) {
            return "A similar regex rule already exists"
        }
        val rule =
            RegexCleanupRule(
                id = existing?.id ?: data.newRuleId(),
                name = name.trim(),
                pattern = normalizedPattern,
                flags = normalizedFlags,
                enabled = existing?.enabled ?: true,
                appliesTo = appliesTo,
            )
        save { data.saveRules(upsert(rules, rule)) }
        return null
    }

    /** Adds or replaces (by [existing]'s id) a rule generated from [preset] with [count]. */
    fun savePresetRule(
        preset: RegexCleanupPresets.Preset,
        count: Int,
        appliesTo: String,
        existing: RegexCleanupRule?,
    ): PresetSave {
        val generated = RegexCleanupPresets.generate(preset, count) ?: return PresetSave.BAD_COUNT
        val rules = data.rules()
        if (RegexRuleCleanup.hasSimilarRegexRule(rules, existing?.id, generated.pattern, generated.flags, appliesTo)) {
            return PresetSave.DUPLICATE
        }
        val rule =
            RegexCleanupRule(
                id = existing?.id ?: data.newRuleId(),
                name = generated.name,
                pattern = generated.pattern,
                flags = generated.flags,
                enabled = existing?.enabled ?: true,
                appliesTo = appliesTo,
            )
        save { data.saveRules(upsert(rules, rule)) }
        return PresetSave.SAVED
    }

    fun toggleRule(id: String) {
        save { data.saveRules(data.rules().map { if (it.id == id) it.copy(enabled = !it.enabled) else it }) }
    }

    fun deleteRule(id: String) {
        save { data.saveRules(data.rules().filterNot { it.id == id }) }
    }

    /** What [pattern]/[flags] leave of [text] (null when nothing to show), computed off the main thread. */
    suspend fun previewRule(
        pattern: String,
        flags: String,
        text: String,
    ): String? = withContext(previewDispatcher) { RegexRuleCleanup.previewRegexRule(pattern, flags, text) }

    private fun upsert(
        rules: List<RegexCleanupRule>,
        rule: RegexCleanupRule,
    ): List<RegexCleanupRule> {
        val index = rules.indexOfFirst { it.id == rule.id }
        return if (index >= 0) rules.toMutableList().apply { set(index, rule) } else rules + rule
    }

    private fun save(write: suspend () -> Unit) {
        scope.launch {
            withContext(NonCancellable) { write() }
            refresh()
        }
    }

    private fun compute(): CleanupUiState = CleanupUiState(mode, data.sentences(), data.rules())
}
