package com.vinicius741.webnovelarchiver.cleanup

import com.vinicius741.webnovelarchiver.domain.model.RegexCleanupRule
import org.jsoup.Jsoup
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import timber.log.Timber
import java.util.concurrent.atomic.AtomicReference

/** Caches compiled cleanup rules and sentence-removal patterns between chapters. */
class CleanupEngine {
    data class CleanupResult(
        val html: String,
        val sentencesRemoved: Int,
        val regexMatchesRemoved: Int = 0,
    )

    data class CompiledRule(
        val regex: Regex,
        val source: RegexCleanupRule,
    )

    /** Immutable snapshot of compiled rules + the prepared sentence list for a given settings version. */
    data class CompiledCleanup(
        val downloadRules: List<CompiledRule>,
        val ttsRules: List<CompiledRule>,
        val sentences: SentenceList,
    )

    private val current = AtomicReference<Pair<Int, CompiledCleanup>?>(null)

    /**
     * Returns the compiled cleanup for the given [sentences] + [rules], recompiling only when either
     * input changed since the last call. Call from download/TTS workers before applying cleanup to a
     * batch of chapters so the regexes are compiled at most once per settings change.
     */
    fun compiled(
        sentences: List<String>,
        rules: List<RegexCleanupRule>,
    ): CompiledCleanup {
        val key = 31 * sentences.hashCode() + rules.hashCode()
        current.get()?.let { (cachedKey, cached) -> if (cachedKey == key) return cached }
        val snapshot = compile(rules, sentences)
        current.set(key to snapshot)
        return snapshot
    }

    private fun compile(
        rules: List<RegexCleanupRule>,
        sentences: List<String>,
    ): CompiledCleanup {
        val downloadRules =
            rules.mapNotNull { rule ->
                if (
                    !rule.enabled ||
                    (rule.appliesTo != "both" && rule.appliesTo != "download") ||
                    RegexCircuitBreaker.isDisabled(rule)
                ) {
                    return@mapNotNull null
                }
                runCatching {
                    Regex(rule.pattern, RegexRuleCleanup.regexOptions(rule.flags))
                }.getOrNull()?.let { compiled -> CompiledRule(compiled, rule) }
            }
        val ttsRules =
            rules.mapNotNull { rule ->
                if (
                    !rule.enabled ||
                    (rule.appliesTo != "both" && rule.appliesTo != "tts") ||
                    RegexCircuitBreaker.isDisabled(rule)
                ) {
                    return@mapNotNull null
                }
                runCatching {
                    Regex(rule.pattern, RegexRuleCleanup.regexOptions(rule.flags))
                }.getOrNull()?.let { compiled -> CompiledRule(compiled, rule) }
            }
        return CompiledCleanup(downloadRules, ttsRules, SentenceList(sentences))
    }

    /** Convenience: apply the cached download cleanup to chapter HTML. */
    fun applyDownload(
        html: String,
        sentences: List<String>,
        rules: List<RegexCleanupRule>,
    ): String = applyDownloadWithStats(html, sentences, rules).html

    /**
     * Reports nonempty matches actually deleted by sentence and download-scoped regex rules. Notices
     * a chapter's [noticeMarker] removes count as sentence matches; the sentence list still runs on
     * marked chapters, since it also holds entries that are not notices.
     *
     * A marked chapter is only parsed when a list entry might occur in it or a regex rule is active,
     * and is only re-serialized when something was removed, so a clean chapter comes back unchanged.
     */
    @Suppress("TooGenericExceptionCaught")
    fun applyDownloadWithStats(
        html: String,
        sentences: List<String>,
        rules: List<RegexCleanupRule>,
        noticeMarker: InsertedNoticeMarker? = null,
    ): CleanupResult {
        val compiled = compiled(sentences, rules)
        val marked = noticeMarker?.strip(html)
        val candidates = compiled.sentences.candidatesIn(marked?.html ?: html)
        val rulesActive = compiled.downloadRules.any { !RegexCircuitBreaker.isDisabled(it.source) }
        if (marked != null && candidates.isEmpty() && !rulesActive) {
            return CleanupResult(marked.html, marked.noticesRemoved)
        }
        val doc = Jsoup.parseBodyFragment(marked?.html ?: html)
        var sentencesRemoved = marked?.noticesRemoved ?: 0
        var regexMatchesRemoved = 0
        val tagsRemoved = doc.select("script,style,noscript,iframe").also { it.remove() }.isNotEmpty()
        val textNodes = traverseTextNodes(doc.body())
        val listSentencesRemoved = removeSentences(textNodes, candidates, compiled.sentences.entries)
        sentencesRemoved += listSentencesRemoved
        // Apply each user regex rule across all text nodes, timing it for the circuit breaker.
        // A pathological pattern most often blows up on a single long node, so timing per-rule across
        // the whole document captures the worst case without re-traversing per rule.
        //
        // Re-check isDisabled per rule here even though compile() filters disabled rules: the compiled
        // set is cached on rules.hashCode() (not the breaker state), so a rule that trips mid-batch is
        // still in the cached set. Skipping it here makes the trip effective on the very next chapter.
        compiled.downloadRules.forEach { compiledRule ->
            val rule = compiledRule.source
            if (RegexCircuitBreaker.isDisabled(rule)) {
                Timber.d("Regex cleanup rule '%s' skipped by circuit breaker", rule.name)
                return@forEach
            }
            val start = System.nanoTime()
            var threw = false
            try {
                textNodes.forEach { node -> regexMatchesRemoved += removeRegexMatches(node, compiledRule.regex) }
            } catch (e: Throwable) {
                threw = true
                // Keep completed edits and continue with other rules; the breaker tracks the failure.
                Timber.w(e, "Regex cleanup rule '%s' threw during application; will be circuit-broken.", rule.name)
            }
            RegexCircuitBreaker.report(rule, System.nanoTime() - start, failed = threw)
        }
        val edited = tagsRemoved || listSentencesRemoved > 0 || regexMatchesRemoved > 0
        val cleaned =
            when {
                edited -> settled(doc.body().html())
                // A stored chapter is Jsoup output already; serializing it again would only cost time.
                marked != null -> marked.html
                else -> doc.body().html()
            }
        return CleanupResult(cleaned, sentencesRemoved, regexMatchesRemoved)
    }

    /**
     * Sentence entries are literals, not user regex, so they cannot backtrack and are not
     * circuit-breaker-tracked. Only nodes holding one of the chapter's [candidates] are edited, and
     * those get the whole list in order, since one removal can join text into another entry.
     */
    private fun removeSentences(
        textNodes: List<TextNode>,
        candidates: List<String>,
        entries: List<String>,
    ): Int {
        if (candidates.isEmpty()) return 0
        var removed = 0
        // When nothing was ruled out, probing with the candidates would only repeat the full pass.
        val probe = candidates.size < entries.size
        textNodes.forEach { node ->
            val text = node.text()
            if (probe && SentenceMatching.remove(text, candidates) == null) return@forEach
            val removal = SentenceMatching.remove(text, entries) ?: return@forEach
            removed += removal.removed
            node.text(removal.text)
        }
        return removed
    }

    /**
     * Returns the nonempty matches deleted from [node]. Counts only a completed node edit, so a later
     * node that throws still leaves earlier nodes counted.
     */
    private fun removeRegexMatches(
        node: TextNode,
        regex: Regex,
    ): Int {
        var removed = 0
        val text = node.text()
        val cleaned =
            regex.replace(text) { match ->
                if (match.value.isNotEmpty()) removed += 1
                ""
            }
        if (cleaned != text) node.text(cleaned)
        return removed
    }

    /**
     * Jsoup's pretty-printer can lay out an edited spot differently the next time the chapter is
     * parsed (a separator that sat alone before a line break, for example), which would make every
     * later cleanup rewrite the chapter for whitespace alone. One more round trip reaches the settled
     * form.
     */
    private fun settled(html: String): String = Jsoup.parseBodyFragment(html).body().html()

    private fun traverseTextNodes(root: Node): List<TextNode> {
        val result = mutableListOf<TextNode>()

        fun visit(node: Node) {
            if (node is TextNode) result.add(node)
            node.childNodes().forEach(::visit)
        }
        visit(root)
        return result
    }

    companion object {
        /** Process-wide shared instance (engines and screens read from this). */
        val shared: CleanupEngine = CleanupEngine()
    }
}
