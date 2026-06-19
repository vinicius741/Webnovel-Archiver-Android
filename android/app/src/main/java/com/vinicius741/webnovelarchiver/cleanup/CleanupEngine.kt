package com.vinicius741.webnovelarchiver.cleanup

import com.vinicius741.webnovelarchiver.domain.model.RegexCleanupRule
import com.vinicius741.webnovelarchiver.ui.text
import org.jsoup.Jsoup
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
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
        val downloadRegexes =
            rules
                .filter { it.enabled && (it.appliesTo == "both" || it.appliesTo == "download") }
                .mapNotNull { runCatching { Regex(it.pattern, TextCleanup.regexOptions(it.flags)) }.getOrNull() }
        val ttsRegexes =
            rules
                .filter { it.enabled && (it.appliesTo == "both" || it.appliesTo == "tts") }
                .mapNotNull { runCatching { Regex(it.pattern, TextCleanup.regexOptions(it.flags)) }.getOrNull() }
        val sentencePatterns =
            sentences.mapNotNull { sentence ->
                val escaped = Regex.escape(sentence.trim()).replace("\\ ", "\\s+")
                if (escaped.isBlank()) null else runCatching { Regex(escaped, setOf(RegexOption.IGNORE_CASE)) }.getOrNull()
            }
        return CompiledCleanup(downloadRegexes, ttsRegexes, sentencePatterns)
    }

    /** Convenience: apply the cached download cleanup to chapter HTML (matches TextCleanup output). */
    fun applyDownload(
        html: String,
        sentences: List<String>,
        rules: List<RegexCleanupRule>,
    ): String = applyDownloadWithStats(html, sentences, rules).html

    /** Applies download cleanup and reports sentence-blocklist matches removed for UI feedback. */
    fun applyDownloadWithStats(
        html: String,
        sentences: List<String>,
        rules: List<RegexCleanupRule>,
    ): CleanupResult {
        val compiled = compiled(sentences, rules)
        val doc = Jsoup.parseBodyFragment(html)
        var sentencesRemoved = 0
        doc.select("script,style,noscript,iframe").remove()
        traverseTextNodes(doc.body()).forEach { node ->
            var text = node.text()
            compiled.sentencePatterns.forEach { pattern ->
                sentencesRemoved += pattern.findAll(text).count()
                text = pattern.replace(text, "")
            }
            compiled.downloadRegexes.forEach { text = it.replace(text, "") }
            node.text(text)
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
