package com.vinicius741.webnovelarchiver.ai

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** A validation problem or warning; [code] matches the Phase-1 spike's codes so runs stay comparable. */
data class RewriteIssue(
    val code: String,
    val detail: String,
)

data class RewriteValidationResult(
    val ok: Boolean,
    val issues: List<RewriteIssue> = emptyList(),
    val warnings: List<RewriteIssue> = emptyList(),
    /** Id-aligned output blocks: merges keep their id with empty html, protected blocks pass through. */
    val blocks: List<ChapterBlock> = emptyList(),
    val sanitizationNotes: List<String> = emptyList(),
    val mergedCount: Int = 0,
    val maxEmptyRun: Int = 0,
)

/**
 * Deterministic validation of a rewrite reply against its source chapter — the merge-semantics
 * contract proven in the Phase-1 spike:
 *
 *  - every input block id exactly once, in order;
 *  - protected blocks byte-identical after whitespace normalization;
 *  - `""` only for addressable blocks with a non-empty addressable carrier above (never across a
 *    protected block or divider);
 *  - more than [MAX_CONSECUTIVE_EMPTY_MERGES] consecutive empty merges is pathological dumping
 *    (dense fragment clusters legitimately produce runs of 4–8, so do not lower this).
 *
 * Truncated or malformed replies must never become drafts: that is enforced by the caller treating
 * `finish_reason == "length"` as a hard reject and by the schema/ids issues returned here.
 */
object ChapterRewriteValidation {
    const val MAX_CONSECUTIVE_EMPTY_MERGES = 12
    private const val MAX_OUTPUT_FACTOR = 3.0

    /**
     * Parses a model reply. Returns null when the body is not a JSON object — an unparseable reply
     * is a validation failure (repairable once), never a crash. Code fences are tolerated.
     */
    fun parseModelReply(content: String): JsonObject? {
        val stripped = stripJsonFences(content)
        val parsed = runCatching { JsonParser.parseString(stripped) }.getOrNull() ?: return null
        return parsed.takeIf { it.isJsonObject }?.asJsonObject
    }

    fun validateRewrite(
        reply: JsonObject,
        chapter: ParsedChapter,
    ): RewriteValidationResult {
        val replyBlocks =
            reply.get("blocks")?.takeIf { it.isJsonArray }?.asJsonArray
                ?: return RewriteValidationResult(false, listOf(RewriteIssue("schema", "missing blocks array")))

        val replyIds = replyBlocks.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject?.string("id") }
        val inputIds = chapter.blocks.map { it.id }
        if (replyIds != inputIds) {
            val missing = inputIds.filter { it !in replyIds }
            val extra = replyIds.filter { it !in inputIds }
            val dupes = replyIds.groupBy { it }.filterValues { it.size > 1 }.keys
            val orderBad = missing.isEmpty() && extra.isEmpty() && dupes.isEmpty()
            val detail =
                buildList {
                    if (missing.isNotEmpty()) add("missing ids: ${missing.take(8)}")
                    if (extra.isNotEmpty()) add("unknown ids: ${extra.take(8)}")
                    if (dupes.isNotEmpty()) add("duplicate ids: ${dupes.take(8)}")
                    if (orderBad) add("ids out of order")
                }.joinToString("; ").ifBlank { "id sequence mismatch" }
            return RewriteValidationResult(false, listOf(RewriteIssue("ids", detail)))
        }

        val state = ValidationState()
        chapter.blocks.zip(replyBlocks.map { it.asJsonObject }).forEach { (src, replyBlock) ->
            val html = replyBlock.string("html")
            when {
                html == null -> return RewriteValidationResult(
                    false,
                    state.issues + RewriteIssue("schema", "${src.id} html is not a string"),
                )
                src.protected -> validateProtectedBlock(state, src, html)
                html.isEmpty() -> validateMergedBlock(state, src)
                else -> validateRewrittenBlock(state, src, html)
            }
        }
        if (state.outWordsTotal < state.srcWordsTotal * 0.4) {
            state.issues.add(RewriteIssue("chapter_shrink", "total prose words ${state.srcWordsTotal}->${state.outWordsTotal}"))
        }
        if (state.maxEmptyRun > MAX_CONSECUTIVE_EMPTY_MERGES) {
            state.issues.add(
                RewriteIssue(
                    "merge_slot_shift",
                    "${state.maxEmptyRun} consecutive empty merges — far above the densest real fragment " +
                        "cluster; likely pathological merge dumping",
                ),
            )
        }

        collectSelfAudit(state, reply)

        return RewriteValidationResult(
            ok = state.issues.isEmpty(),
            issues = state.issues,
            warnings = state.warnings,
            blocks = state.outBlocks,
            sanitizationNotes = state.notes,
            mergedCount = state.mergedCount,
            maxEmptyRun = state.maxEmptyRun,
        )
    }

    /** Mutable accumulator so the per-block handlers stay small enough for detekt budgets. */
    private class ValidationState {
        val issues = mutableListOf<RewriteIssue>()
        val warnings = mutableListOf<RewriteIssue>()
        val notes = mutableListOf<String>()
        val outBlocks = mutableListOf<ChapterBlock>()
        var srcWordsTotal = 0
        var outWordsTotal = 0
        var mergedCount = 0
        var consecutiveEmpties = 0
        var maxEmptyRun = 0
    }

    private fun validateProtectedBlock(
        state: ValidationState,
        src: ChapterBlock,
        html: String,
    ) {
        state.consecutiveEmpties = 0
        when {
            html.isEmpty() -> {
                state.issues.add(RewriteIssue("protected_merged", "${src.id} (${src.reason}) returned empty but is protected"))
                state.outBlocks.add(src)
            }
            ChapterBlockParsing.normalizeForCompare(html) != ChapterBlockParsing.normalizeForCompare(src.html) -> {
                state.issues.add(RewriteIssue("protected_changed", "${src.id} (${src.reason}) differs from source"))
                state.outBlocks.add(src)
            }
            else -> state.outBlocks.add(src)
        }
    }

    /** Merge semantics (prompt v1.1): content absorbed into the previous addressable block. */
    private fun validateMergedBlock(
        state: ValidationState,
        src: ChapterBlock,
    ) {
        state.consecutiveEmpties++
        state.maxEmptyRun = maxOf(state.maxEmptyRun, state.consecutiveEmpties)
        // Chained merges (through earlier empty blocks) are allowed; a protected block above
        // blocks the merge entirely.
        var target: ChapterBlock? = null
        for (prev in state.outBlocks.asReversed()) {
            if (prev.protected) break
            if (prev.html.isNotEmpty()) {
                target = prev
                break
            }
        }
        if (target == null) {
            state.issues.add(RewriteIssue("merge_without_target", "${src.id} merged with no addressable carrier above"))
        } else {
            state.mergedCount++
        }
        state.outBlocks.add(ChapterBlock(src.id, src.tag, "", false, "merged"))
        state.srcWordsTotal += ChapterCadenceReport.words(ChapterBlockParsing.textOf(src.html)).size
    }

    private fun validateRewrittenBlock(
        state: ValidationState,
        src: ChapterBlock,
        html: String,
    ) {
        state.consecutiveEmpties = 0
        val (clean, blockNotes) = ChapterBlockParsing.sanitizeOutputBlock(html)
        blockNotes.forEach { note -> state.notes.add("${src.id}: $note") }
        val outWords = ChapterCadenceReport.words(ChapterBlockParsing.textOf(clean)).size
        val srcWords = ChapterCadenceReport.words(ChapterBlockParsing.textOf(src.html)).size
        state.srcWordsTotal += srcWords
        state.outWordsTotal += outWords
        if (outWords == 0) state.issues.add(RewriteIssue("empty_prose", "${src.id} sanitized to nothing"))
        if (srcWords >= 20 && outWords < srcWords * 0.4) {
            state.warnings.add(RewriteIssue("severe_shrink", "${src.id}: $srcWords->$outWords words"))
        }
        if (outWords > srcWords * MAX_OUTPUT_FACTOR + 30) {
            state.warnings.add(RewriteIssue("severe_growth", "${src.id}: $srcWords->$outWords words"))
        }
        state.outBlocks.add(ChapterBlock(src.id, src.tag, clean, false, "rewritten"))
    }

    private fun collectSelfAudit(
        state: ValidationState,
        reply: JsonObject,
    ) {
        val audit = reply.get("self_audit")?.takeIf { it.isJsonObject }?.asJsonObject
        if (audit?.get("protected_blocks_unchanged")?.takeIf { it.isJsonPrimitive }?.asBoolean != true) {
            state.warnings.add(RewriteIssue("self_audit_flag", "model reports protected blocks may have changed"))
        }
        audit?.get("possible_drift")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { drift ->
            state.warnings.add(RewriteIssue("self_audit_drift", drift.toString()))
        }
    }

    /** One preservation finding from the verifier model (severity: blocker | warning). */
    data class VerifierFinding(
        val severity: String,
        val type: String,
        val blockIds: List<String>,
        val evidence: String,
    )

    /**
     * Parsed verifier verdict; [parseError] is non-null when the reply was unparseable or not the
     * expected shape (a failure, never a pass — a schema-ignoring reply must not read as "clean").
     */
    data class VerifierVerdict(
        val findings: List<VerifierFinding>,
        val parseError: String? = null,
    )

    fun parseVerifierVerdict(content: String): VerifierVerdict {
        val parsed =
            parseModelReply(content)
                ?: return VerifierVerdict(emptyList(), parseError = "verifier reply unparseable")
        val findingsArray =
            parsed.get("findings")?.takeIf { it.isJsonArray }?.asJsonArray
                ?: return VerifierVerdict(emptyList(), parseError = "verifier reply missing findings array")
        val findings = mutableListOf<VerifierFinding>()
        findingsArray.forEach { element ->
            val finding = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val severity = finding.string("severity") ?: return@forEach
            findings.add(
                VerifierFinding(
                    severity = severity,
                    type = finding.string("type").orEmpty(),
                    blockIds =
                        finding
                            .get("block_ids")
                            ?.takeIf { it.isJsonArray }
                            ?.asJsonArray
                            ?.mapNotNull { id -> id.takeIf { it.isJsonPrimitive }?.asString }
                            .orEmpty(),
                    evidence = finding.string("evidence").orEmpty(),
                ),
            )
        }
        return VerifierVerdict(findings)
    }

    fun blockersOf(verdict: VerifierVerdict): List<VerifierFinding> = verdict.findings.filter { it.severity == "blocker" }

    private fun stripJsonFences(text: String): String {
        var value = text.trim()
        if (value.startsWith("```")) {
            value = value.replaceFirst(Regex("^```(?:json)?\\s*"), "")
            value = value.replace(Regex("\\s*```$"), "")
        }
        return value.trim()
    }

    private fun JsonObject.string(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
}
