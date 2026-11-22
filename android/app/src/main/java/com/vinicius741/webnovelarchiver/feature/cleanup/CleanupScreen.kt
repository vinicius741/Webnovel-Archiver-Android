package com.vinicius741.webnovelarchiver.feature.cleanup

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import com.vinicius741.webnovelarchiver.data.repository.AppRepository
import com.vinicius741.webnovelarchiver.domain.model.RegexCleanupRule
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.SegmentedSelector
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.bindWhileAttached
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.spacer
import com.vinicius741.webnovelarchiver.ui.verticalFill

/** The repository's cleanup lists. */
internal class RepositoryCleanupRulesData(
    private val repository: AppRepository,
) : CleanupRulesData {
    override fun sentences(): List<String> = repository.sentenceRemovalList.get()

    override fun rules(): List<RegexCleanupRule> = repository.regexRules.get()

    override suspend fun saveSentences(sentences: List<String>) = repository.sentenceRemovalList.save(sentences)

    override suspend fun saveRules(rules: List<RegexCleanupRule>) = repository.regexRules.save(rules)

    override fun newRuleId(): String = "rule_${System.currentTimeMillis()}"
}

/** The Cleanup screen's model, kept while it is in the history; it starts from the memory saved before a process death. */
private fun ScreenHost.cleanupModel(): CleanupScreenModel {
    val key = AppRoute.CleanupRules.stableKey
    return screenModels.get(key) { CleanupScreenModel(RepositoryCleanupRulesData(repository), screenModels.takeRestored(key)) }
}

/**
 * Text cleanup: a mode switch (full sentences or regex rules) above that mode's scrolling list,
 * built from [CleanupScreenModel]'s state. A change of mode or of either list rebuilds the page.
 */
internal fun ScreenHost.showCleanupRules() {
    screen(
        route = AppRoute.CleanupRules,
        title = "Text cleanup",
        subtitle = "Applied to read-aloud and downloaded text",
    ) {
        // screen() navigates to this route before it prunes the store, so the model is kept.
        val model = cleanupModel()
        val state = model.refresh()
        val mode = state.mode
        // The segmented control stays above the scrolling pane, even at the bottom of a long list.
        val counts = listOf(state.sentences.size, state.rules.size)
        val modes = CleanupMode.entries
        addView(
            SegmentedSelector(context, modes.map { it.label }, modes.indexOf(mode), counts) { index ->
                model.selectMode(modes[index])
            }.view,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(Space.XS)
            },
        )
        val body =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                spacer(Space.LG)
            }
        when (mode) {
            CleanupMode.SENTENCES -> buildSentenceRules(body, model, state.sentences)
            CleanupMode.REGEX -> buildRegexRules(body, model, state.rules)
        }
        addView(
            ScrollView(context).apply {
                isFillViewport = true
                addView(body)
                val scrollY = model.scrollOf(mode)
                post { scrollTo(0, scrollY) }
                setOnScrollChangeListener { _, _, y, _, _ -> model.rememberScroll(mode, y) }
            },
            verticalFill(),
        )
        val root: View = this
        root.bindWhileAttached(scope, model.state) { next -> if (next != state) showCleanupRules() }
    }
}
