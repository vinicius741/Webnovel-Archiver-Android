package com.vinicius741.webnovelarchiver.cleanup

import com.vinicius741.webnovelarchiver.domain.model.RegexCleanupRule
import com.vinicius741.webnovelarchiver.ui.text
import org.jsoup.Jsoup
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import timber.log.Timber
import java.util.concurrent.atomic.AtomicReference

/**
 * Caches compiled cleanup rules (Speed S6). Download and TTS cleanup compile the configured regex
 * rules + sentence-removal patterns on every chapter; across hundreds of chapters that is wasted
 * work. This engine compiles them once and recompiles only when the underlying settings change.
 *
 * Thread-safe via [AtomicReference] swap; callers read the current compiled snapshot without locking.
 * [TextCleanup] keeps its stateless compile-on-demand entry points for tests and one-off use; the
 * engine is the shared, cached path for hot loops (download batches, TTS preparation).
 */
class CleanupEngine {
    data class CleanupResult(
        val html: String,
        val sentencesRemoved: Int,
    )

    /** Immutable snapshot of compiled rules + sentence patterns for a given settings version. */
    data class CompiledCleanup(
        val downloadRegexes: List<Regex>,
        val ttsRegexes: List<Regex>,
        val sentencePatterns: List<Regex>,
        /** Source rules paired with their compiled regexes (download target), for circuit-breaker reporting. */
        val downloadRuleRefs: List<RegexCleanupRule> = emptyList(),
        /** Source rules paired with their compiled regexes (tts target), for circuit-breaker reporting. */
        val ttsRuleRefs: List<RegexCleanupRule> = emptyList(),
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
        val downloadRegexes = mutableListOf<Regex>()
        val downloadRuleRefs = mutableListOf<RegexCleanupRule>()
        rules
            .filter { it.enabled && (it.appliesTo == "both" || it.appliesTo == "download") && !RegexCircuitBreaker.isDisabled(it) }
            .forEach { rule ->
                runCatching { Regex(rule.pattern, TextCleanup.regexOptions(rule.flags)) }.getOrNull()?.let {
                    downloadRegexes.add(it)
                    downloadRuleRefs.add(rule)
                }
            }
        val ttsRegexes = mutableListOf<Regex>()
        val ttsRuleRefs = mutableListOf<RegexCleanupRule>()
        rules
            .filter { it.enabled && (it.appliesTo == "both" || it.appliesTo == "tts") && !RegexCircuitBreaker.isDisabled(it) }
            .forEach { rule ->
                runCatching { Regex(rule.pattern, TextCleanup.regexOptions(rule.flags)) }.getOrNull()?.let {
                    ttsRegexes.add(it)
                    ttsRuleRefs.add(rule)
                }
            }
        val sentencePatterns =
            sentences.mapNotNull { sentence ->
                val escaped = Regex.escape(sentence.trim()).replace("\\ ", "\\s+")
                if (escaped.isBlank()) null else runCatching { Regex(escaped, setOf(RegexOption.IGNORE_CASE)) }.getOrNull()
            }
        return CompiledCleanup(downloadRegexes, ttsRegexes, sentencePatterns, downloadRuleRefs, ttsRuleRefs)
    }

    /** Convenience: apply the cached download cleanup to chapter HTML (matches TextCleanup output). */
    fun applyDownload(
        html: String,
        sentences: List<String>,
        rules: List<RegexCleanupRule>,
    ): String = applyDownloadWithStats(html, sentences, rules).html

    /**
     * Applies download cleanup and reports sentence-blocklist matches removed for UI feedback.
     *
     * Rec 7: a user regex can throw StackOverflowError/etc. on a pathological input; catch broadly
     * to circuit-break the rule rather than abort cleanup.
     */
    @Suppress("TooGenericExceptionCaught")
    fun applyDownloadWithStats(
        html: String,
        sentences: List<String>,
        rules: List<RegexCleanupRule>,
    ): CleanupResult {
        val compiled = compiled(sentences, rules)
        val doc = Jsoup.parseBodyFragment(html)
        var sentencesRemoved = 0
        doc.select("script,style,noscript,iframe").remove()
        val textNodes = traverseTextNodes(doc.body())
        // Sentence-blocklist patterns are author-controlled (escaped literals), not user regex, so
        // they are not ReDoS candidates and are not circuit-breaker-tracked.
        textNodes.forEach { node ->
            var text = node.text()
            compiled.sentencePatterns.forEach { pattern ->
                sentencesRemoved += pattern.findAll(text).count()
                text = pattern.replace(text, "")
            }
            node.text(text)
        }
        // Apply each user regex rule across all text nodes, timing it for the circuit breaker (Rec 7).
        // A pathological pattern most often blows up on a single long node, so timing per-rule across
        // the whole document captures the worst case without re-traversing per rule.
        //
        // Re-check isDisabled per rule here even though compile() filters disabled rules: the compiled
        // set is cached on rules.hashCode() (not the breaker state), so a rule that trips mid-batch is
        // still in the cached set. Skipping it here makes the trip effective on the very next chapter.
        compiled.downloadRegexes.forEachIndexed { index, regex ->
            val rule = compiled.downloadRuleRefs.getOrNull(index)
            if (rule != null && RegexCircuitBreaker.isDisabled(rule)) return@forEachIndexed
            val start = System.nanoTime()
            var threw = false
            try {
                textNodes.forEach { node -> node.text(regex.replace(node.text(), "")) }
            } catch (e: Throwable) {
                threw = true
                // Re-run with a defensive fallback so one rule's failure does not abort cleanup. The
                // breaker will disable it after enough strikes.
                Timber.w(e, "Regex cleanup rule '%s' threw during application; will be circuit-broken.", rule?.name)
            }
            if (rule != null) RegexCircuitBreaker.report(rule, System.nanoTime() - start, failed = threw)
        }
        return CleanupResult(doc.body().html(), sentencesRemoved)
    }

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
