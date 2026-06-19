package com.vinicius741.webnovelarchiver.cleanup

import com.vinicius741.webnovelarchiver.domain.model.RegexCleanupRule
import com.vinicius741.webnovelarchiver.tts.TtsEngine
import com.vinicius741.webnovelarchiver.ui.row
import com.vinicius741.webnovelarchiver.ui.text
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * HTML cleanup, regex rule validation, and TTS chunking (Maintainability M1: split out of Engines.kt).
 * Stateful, cached application now also flows through [CleanupEngine]; this object keeps the
 * stateless, tested entry points.
 */
object TextCleanup {
    private val regexFlagOrder = listOf('g', 'i', 'm', 's', 'u')
    private val allowedRegexFlags = regexFlagOrder.toSet()
    private val regexLiteral = Regex("^/((?:\\\\.|[^\\\\/])*)/([a-z]*)$", RegexOption.IGNORE_CASE)
    private val riskyRegexChecks =
        listOf(
            Regex("\\((?:[^()\\\\]|\\\\.)*[+*](?:[^()\\\\]|\\\\.)*\\)\\s*(?:\\+|\\*|\\{\\d*,?\\d*\\})"),
            Regex("\\((?:[^()\\\\]|\\\\.)*\\.\\*(?:[^()\\\\]|\\\\.)*\\)\\s*(?:\\+|\\*|\\{\\d*,?\\d*\\})"),
            Regex("\\((?:[^()\\\\]|\\\\.)*\\\\\\d+(?:[^()\\\\]|\\\\.)*\\)\\s*(?:\\+|\\*|\\{\\d*,?\\d*\\})"),
        )
    private const val maxRegexPatternLength = 500
    private const val maxRuleNameLength = 80

    data class RegexValidationResult(
        val valid: Boolean,
        val error: String? = null,
        val normalizedPattern: String? = null,
        val normalizedFlags: String? = null,
    )

    data class QuickPattern(
        val pattern: String,
        val flags: String,
        val name: String,
    )

    fun sanitizeRegexRules(rules: List<RegexCleanupRule>): MutableList<RegexCleanupRule> {
        val unique = linkedMapOf<String, RegexCleanupRule>()
        rules.forEach { rule ->
            val id = rule.id.trim()
            if (id.isBlank()) return@forEach
            val name = rule.name.trim()
            val validation = validateRegexRule(name, rule.pattern, rule.flags)
            if (!validation.valid) return@forEach
            val appliesTo =
                when (rule.appliesTo.trim().lowercase()) {
                    "download", "tts", "both" -> rule.appliesTo.trim().lowercase()
                    else -> "both"
                }
            unique[id] =
                rule.copy(
                    id = id,
                    name = name,
                    pattern = validation.normalizedPattern ?: rule.pattern.trim(),
                    flags = validation.normalizedFlags.orEmpty(),
                    appliesTo = appliesTo,
                )
        }
        return unique.values.toMutableList()
    }

    fun hasSimilarRegexRule(
        rules: List<RegexCleanupRule>,
        currentId: String?,
        normalizedPattern: String,
        normalizedFlags: String,
        appliesTo: String,
    ): Boolean {
        val target =
            when (appliesTo.trim().lowercase()) {
                "download", "tts", "both" -> appliesTo.trim().lowercase()
                else -> "both"
            }
        return rules.any { rule ->
            rule.id != currentId &&
                rule.pattern == normalizedPattern &&
                rule.flags == normalizedFlags &&
                rule.appliesTo == target
        }
    }

    fun applyDownloadCleanup(
        html: String,
        sentences: List<String>,
        rules: List<RegexCleanupRule>,
    ): String {
        val doc = Jsoup.parseBodyFragment(html)
        val sentencePatterns =
            sentences.mapNotNull { sentence ->
                val escaped = Regex.escape(sentence.trim()).replace("\\ ", "\\s+")
                if (escaped.isBlank()) null else runCatching { Regex(escaped, setOf(RegexOption.IGNORE_CASE)) }.getOrNull()
            }
        val regexRules =
            rules.filter { it.enabled && (it.appliesTo == "both" || it.appliesTo == "download") }.mapNotNull {
                runCatching {
                    Regex(it.pattern, regexOptions(it.flags))
                }.getOrNull()
            }
        doc.select("script,style,noscript,iframe").remove()
        doc.body().traverseTextNodes().forEach { node ->
            var text = node.text()
            sentencePatterns.forEach { text = it.replace(text, "") }
            regexRules.forEach { text = it.replace(text, "") }
            node.text(text)
        }
        return doc.body().html()
    }

    fun htmlToPlainText(html: String): String {
        val doc = Jsoup.parseBodyFragment(html)
        doc.select("script,style,noscript,iframe").remove()
        doc.select("p,div,br,h1,h2,h3,h4,h5,h6,li").append(" ")
        return doc
            .body()
            .text()
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun htmlToFormattedText(html: String): String {
        if (html.isBlank()) return ""
        val doc = Jsoup.parseBodyFragment(html)
        doc.select("script,style,noscript,iframe").remove()
        val lines = mutableListOf<String>()

        fun emit(
            text: String,
            major: Boolean = false,
        ) {
            val value = text.replace(Regex("[ \\t]+"), " ").trim()
            if (value.isBlank()) return
            if (major && lines.isNotEmpty() && lines.last().isNotBlank()) lines.add("")
            lines.add(value)
            if (major) lines.add("")
        }

        fun collectInline(element: Element): String {
            val parts = mutableListOf<String>()
            element.childNodes().forEach { child ->
                when (child) {
                    is TextNode -> parts.add(child.text())
                    is Element -> {
                        when (child.tagName().lowercase()) {
                            "br" -> parts.add("\n")
                            "script", "style", "noscript", "iframe" -> Unit
                            "td", "th" -> parts.add(collectInline(child))
                            else -> parts.add(collectInline(child))
                        }
                    }
                }
            }
            return parts
                .joinToString("")
                .replace(Regex("[ \\t]*\\n[ \\t]*"), "\n")
                .replace(Regex("[ \\t]+"), " ")
                .trim()
        }

        val blockTags = setOf("p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "blockquote", "table")

        fun walk(element: Element) {
            when (element.tagName().lowercase()) {
                "table", "tbody", "thead" -> element.children().forEach(::walk)
                "tr" -> {
                    val row =
                        element
                            .children()
                            .filter { it.tagName().equals("td", true) || it.tagName().equals("th", true) }
                            .joinToString(" | ") { collectInline(it) }
                    emit(row)
                }
                "p", "li" -> emit(collectInline(element))
                "blockquote", "h1", "h2", "h3", "h4", "h5", "h6" -> emit(collectInline(element), major = true)
                "div" -> {
                    val blockChildren =
                        element.children().filter { child ->
                            child.tagName().lowercase() in blockTags
                        }
                    if (blockChildren.isNotEmpty() && element.ownText().isBlank()) {
                        element.children().forEach(::walk)
                    } else {
                        emit(collectInline(element))
                    }
                }
                "ul", "ol" -> element.children().forEach(::walk)
                "body" -> {
                    val hasBlockChildren = element.children().any { it.tagName().lowercase() in blockTags }
                    if (hasBlockChildren) element.children().forEach(::walk) else emit(collectInline(element))
                }
                else -> {
                    if (element.children().isEmpty()) emit(collectInline(element)) else element.children().forEach(::walk)
                }
            }
        }

        walk(doc.body())
        return lines
            .joinToString("\n")
            .replace(Regex("[ \\t]+\\n"), "\n")
            .replace(Regex("\\n[ \\t]+"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trimEnd()
    }

    fun prepareTtsChunks(
        html: String,
        regexRules: List<RegexCleanupRule>,
        chunkSize: Int,
    ): List<String> {
        if (html.isBlank()) return emptyList()

        val doc = Jsoup.parseBodyFragment(html)
        doc.select("script,style,noscript,iframe").remove()
        val cleanupForDisplay = regexRunner(regexRules, "download")
        val cleanupForTts = regexRunner(regexRules, "tts")
        val chunks = mutableListOf<String>()
        var current = ""
        val effectiveChunkSize = chunkSize.coerceAtLeast(100)

        val elements = doc.select("p,h1,h2,h3,h4,h5,h6,li,blockquote,div")
        elements.forEach { element ->
            if (
                element.tagName().equals("div", ignoreCase = true) &&
                element.select("> p, > div, > h1, > h2, > h3, > h4, > h5, > h6, > ul, > ol, > blockquote").isNotEmpty()
            ) {
                return@forEach
            }

            val displayText = cleanupForDisplay(element.text()).replace(Regex("\\s+"), " ").trim()
            if (displayText.isBlank()) return@forEach

            val ttsText = cleanupForTts(displayText).replace(Regex("\\s+"), " ").trim()
            if (ttsText.isBlank()) return@forEach

            if (current.isNotBlank() && current.length + 1 + ttsText.length > effectiveChunkSize) {
                chunks.add(current)
                current = ""
            }
            current = if (current.isBlank()) ttsText else "$current $ttsText"
        }

        if (current.isNotBlank()) {
            chunks.add(current)
        }

        if (chunks.isNotEmpty()) return chunks

        val fallback =
            cleanupForTts(cleanupForDisplay(doc.body().text()))
                .replace(Regex("\\s+"), " ")
                .trim()
        return if (fallback.isBlank()) emptyList() else fallback.chunked(effectiveChunkSize)
    }

    /** Result of [prepareTtsAnnotatedHtml]: the chunk-tagged HTML plus the aligned chunk list. */
    data class TtsAnnotatedHtml(
        val annotatedHtml: String,
        val chunks: List<String>,
    )

    /**
     * Produces TTS-aware reader HTML (parity gap 3): a port of the legacy RN `prepareTTSContent`.
     *
     * Walks the same block elements as [prepareTtsChunks] using the identical grouping rule, so each
     * contributing element is tagged with `data-tts-group="<index>"` + the `tts-chunk` class, and the
     * returned [chunks] list is byte-for-byte aligned with [prepareTtsChunks]'s output — meaning a
     * chunk index the engine reports (e.g. via [TtsEngine.playFromChunk] / the playback snapshot)
     * maps 1:1 to the `data-tts-group` the reader's highlight script highlights.
     *
     * When no block elements match, falls back to escaped inline spans with matching group indices,
     * so plain-text fragments still highlight and support tap-to-start.
     */
    fun prepareTtsAnnotatedHtml(
        html: String,
        regexRules: List<RegexCleanupRule>,
        chunkSize: Int,
    ): TtsAnnotatedHtml {
        if (html.isBlank()) return TtsAnnotatedHtml("", emptyList())

        val doc = Jsoup.parseBodyFragment(html)
        doc.select("script,style,noscript,iframe").remove()
        val cleanupForDisplay = regexRunner(regexRules, "download")
        val cleanupForTts = regexRunner(regexRules, "tts")
        val chunks = mutableListOf<String>()
        var current = ""
        var groupIndex = 0
        val effectiveChunkSize = chunkSize.coerceAtLeast(100)
        var taggedAny = false

        val elements = doc.select("p,h1,h2,h3,h4,h5,h6,li,blockquote,div")
        elements.forEach { element ->
            if (
                element.tagName().equals("div", ignoreCase = true) &&
                element.select("> p, > div, > h1, > h2, > h3, > h4, > h5, > h6, > ul, > ol, > blockquote").isNotEmpty()
            ) {
                return@forEach
            }

            val displayText = cleanupForDisplay(element.text()).replace(Regex("\\s+"), " ").trim()
            if (displayText.isBlank()) return@forEach

            val ttsText = cleanupForTts(displayText).replace(Regex("\\s+"), " ").trim()
            if (ttsText.isBlank()) return@forEach

            if (current.isNotBlank() && current.length + 1 + ttsText.length > effectiveChunkSize) {
                chunks.add(current)
                current = ""
                groupIndex += 1
            }
            // Tag this element with the group its text belongs to, so the reader's highlight script
            // can resolve "currently speaking group N" back to a DOM node.
            element.attr("data-tts-group", groupIndex.toString())
            element.addClass("tts-chunk")
            taggedAny = true

            current = if (current.isBlank()) ttsText else "$current $ttsText"
        }

        if (current.isNotBlank()) {
            chunks.add(current)
        }

        if (taggedAny) {
            return TtsAnnotatedHtml(doc.body().html(), chunks)
        }

        // Fallback (mirrors prepareTtsChunks): no block elements contributed — collapse to body text.
        val fallback =
            cleanupForTts(cleanupForDisplay(doc.body().text()))
                .replace(Regex("\\s+"), " ")
                .trim()
        return if (fallback.isBlank()) {
            TtsAnnotatedHtml(doc.body().html(), emptyList())
        } else {
            val fallbackChunks = fallback.chunked(effectiveChunkSize)
            val fallbackDoc = Jsoup.parseBodyFragment("")
            fallbackChunks.forEachIndexed { index, chunk ->
                fallbackDoc
                    .body()
                    .appendElement("span")
                    .attr("data-tts-group", index.toString())
                    .addClass("tts-chunk")
                    .text(chunk)
                if (index < fallbackChunks.lastIndex) {
                    fallbackDoc.body().appendText(" ")
                }
            }
            TtsAnnotatedHtml(fallbackDoc.body().html(), fallbackChunks)
        }
    }

    fun validateRegexRule(
        name: String,
        patternInput: String,
        flagsInput: String,
    ): RegexValidationResult {
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) return RegexValidationResult(false, "Rule name is required.")
        if (trimmedName.length > maxRuleNameLength) {
            return RegexValidationResult(false, "Rule name must be $maxRuleNameLength characters or fewer.")
        }

        val normalizedInput = normalizeRegexInput(patternInput, flagsInput)
        normalizedInput.error?.let { return RegexValidationResult(false, it) }
        val pattern = normalizedInput.pattern
        val flags = normalizedInput.flags

        if (pattern.isBlank()) return RegexValidationResult(false, "Regex pattern is required.")
        if (pattern.length > maxRegexPatternLength) {
            return RegexValidationResult(false, "Regex pattern must be $maxRegexPatternLength characters or fewer.")
        }
        flags.forEach { flag ->
            if (flag !in allowedRegexFlags) {
                return RegexValidationResult(false, "Unsupported regex flag: \"$flag\". Allowed flags: ${regexFlagOrder.joinToString("")}")
            }
        }
        if (riskyRegexChecks.any { it.containsMatchIn(pattern) }) {
            return RegexValidationResult(false, "Unsafe regex pattern: nested quantified groups can cause very slow matching.")
        }

        val normalizedFlags = normalizeRegexFlags(flags)
        return runCatching {
            Regex(pattern, regexOptions(normalizedFlags.replace("g", "")))
            RegexValidationResult(true, normalizedPattern = pattern, normalizedFlags = normalizedFlags)
        }.getOrElse { RegexValidationResult(false, "Invalid regex: ${it.message}") }
    }

    fun generateQuickPattern(
        characters: String,
        minCount: Int,
        wholeLine: Boolean,
    ): QuickPattern? {
        val value = characters.trim()
        if (value.isBlank() || minCount < 1) return null
        val escaped = escapeRegexLiteral(value)
        val core = if (value.length > 1) "(?:$escaped){$minCount,}" else "$escaped{$minCount,}"
        val pattern = if (wholeLine) "^[\\s]*$core[\\s]*$" else core
        val flags = if (wholeLine) "gm" else "g"
        val display = if (value.length > 4) "${value.take(4)}..." else value
        val scope = if (wholeLine) "separator lines" else "patterns"
        return QuickPattern(pattern, flags, "Remove $display ($minCount+) $scope")
    }

    private fun regexRunner(
        rules: List<RegexCleanupRule>,
        target: String,
    ): (String) -> String {
        val compiled =
            rules
                .filter { it.enabled && (it.appliesTo == "both" || it.appliesTo == target) }
                .mapNotNull { rule ->
                    runCatching {
                        Regex(rule.pattern, regexOptions(rule.flags))
                    }.getOrNull()
                }
        return { input -> compiled.fold(input) { text, regex -> regex.replace(text, "") } }
    }

    /**
     * Preview helper for the regex rule editor (parity gap 5): validates the in-progress rule, then
     * applies it to [sampleInput] and returns the cleaned text — `null` when the rule is invalid or
     * the input is blank. Mirrors the legacy RN `RuleDialog` "Test Preview" pane (the draft is
     * treated as `appliesTo = both`, exactly as the RN version did).
     */
    fun previewRegexRule(
        pattern: String,
        flags: String,
        sampleInput: String,
    ): String? {
        if (sampleInput.isBlank()) return null
        val validation = validateRegexRule("preview", pattern, flags)
        if (!validation.valid) return null
        val normalizedPattern = validation.normalizedPattern ?: return null
        val normalizedFlags = validation.normalizedFlags ?: ""
        val compiled =
            runCatching {
                Regex(normalizedPattern, regexOptions(normalizedFlags))
            }.getOrNull() ?: return null
        return compiled.replace(sampleInput, "")
    }

    private data class NormalizedRegexInput(
        val pattern: String,
        val flags: String,
        val error: String? = null,
    )

    private fun normalizeRegexInput(
        patternInput: String,
        flagsInput: String,
    ): NormalizedRegexInput {
        val trimmedPattern = patternInput.trim()
        val trimmedFlags = flagsInput.trim().lowercase()
        val maybeLiteral = trimmedPattern.startsWith("/") && trimmedPattern.lastIndexOf("/") > 0
        val match = regexLiteral.matchEntire(trimmedPattern)

        if (match == null && maybeLiteral) {
            return NormalizedRegexInput(
                trimmedPattern,
                trimmedFlags,
                "Invalid regex literal. Use /pattern/flags or provide pattern and flags separately.",
            )
        }
        if (match == null) return NormalizedRegexInput(trimmedPattern, trimmedFlags)

        return NormalizedRegexInput(match.groupValues[1], trimmedFlags + match.groupValues[2].lowercase())
    }

    private fun normalizeRegexFlags(flags: String): String {
        val unique = flags.trim().lowercase().toSet()
        return regexFlagOrder.filter { it in unique }.joinToString("")
    }

    internal fun regexOptions(flags: String): Set<RegexOption> {
        val opts = mutableSetOf<RegexOption>()
        if ('i' in flags) opts.add(RegexOption.IGNORE_CASE)
        if ('m' in flags) opts.add(RegexOption.MULTILINE)
        if ('s' in flags) opts.add(RegexOption.DOT_MATCHES_ALL)
        return opts
    }

    private fun escapeRegexLiteral(value: String): String {
        val special = setOf('.', '*', '+', '?', '^', '$', '{', '}', '(', ')', '|', '[', ']', '\\', '-')
        return buildString {
            value.forEach { char ->
                if (char in special) append('\\')
                append(char)
            }
        }
    }

    private fun Node.traverseTextNodes(): List<TextNode> {
        val result = mutableListOf<TextNode>()

        fun visit(node: Node) {
            if (node is TextNode) {
                result.add(node)
            }
            node.childNodes().forEach(::visit)
        }
        visit(this)
        return result
    }
}
