package com.vinicius741.webnovelarchiver.domain.model

// Chapter-rewrite persistence models. Every field has a default so legacy JSON and R8/keeps stay
// compatible (Gson + no-arg constructor); money stays a decimal string, never a float.
//
// Layout under `files/webnovel_archiver/chapter_rewrites/<safeStoryId>/`:
// `manifest.json` (atomic, the completeness marker), then `<safeChapterId>/draft.html` and
// `<safeChapterId>/applied.html`. Rewrite metadata deliberately stays out of [Story.chapters].

/** Edit strength; Light is the default (the blind ballot favored the least-intervention rewrites). */
enum class RewriteStrength(
    val wire: String,
    val label: String,
) {
    LIGHT("light", "Light"),
    BALANCED("balanced", "Balanced"),
    ;

    companion object {
        fun fromWire(value: String?): RewriteStrength? = entries.firstOrNull { it.wire == value }
    }
}

/** One preservation finding reported by the verifier model. */
data class RewriteVerificationFinding(
    val severity: String = "",
    val type: String = "",
    val blockIds: List<String> = emptyList(),
    val evidence: String = "",
)

/** Verifier outcome snapshot stored with each rewrite. */
data class RewriteVerificationSummary(
    /** "verified" | "blocked" | "verify_failed". */
    val status: String = "verify_failed",
    val blockerCount: Int = 0,
    val findings: List<RewriteVerificationFinding> = emptyList(),
    val verifierModel: String = "",
    val verifierPromptVersion: String = "",
)

/** Compact before/after cadence numbers for the comparison screen (computed from parsed blocks). */
data class RewriteCadenceSummary(
    val fragmentShareBefore: Double = 0.0,
    val fragmentShareAfter: Double = 0.0,
    val clusterCountBefore: Int = 0,
    val clusterCountAfter: Int = 0,
    val tripletCountBefore: Int = 0,
    val tripletCountAfter: Int = 0,
    val sentenceLengthCvBefore: Double = 0.0,
    val sentenceLengthCvAfter: Double = 0.0,
    val templateSwapWarning: Boolean = false,
    val templateSwapDetail: String = "",
)

/** The applied (or appliable) polished variant of one chapter. Immutable once written. */
data class AppliedChapterRewrite(
    val storyId: String = "",
    val chapterId: String = "",
    val chapterTitle: String = "",
    /** Hash of the sanitized source this rewrite was generated from; mismatch = Out of date. */
    val sourceSha256: String = "",
    val appliedAt: Long = 0,
    val createdAt: Long = 0,
    val model: String = "",
    val verifierModel: String = "",
    val promptVersion: String = "",
    val strength: String = RewriteStrength.LIGHT.wire,
    val operationId: String = "",
    val costUsd: String? = null,
    val verification: RewriteVerificationSummary = RewriteVerificationSummary(),
    val mergedBlocks: Int = 0,
    /** Which provider routing tier produced the rewrite: "strict" | "relaxed" | "none". */
    val providerTier: String = "strict",
    /** Reader/TTS use this version while true; the source chapter file is never touched. */
    val active: Boolean = true,
    /** Directory name under the story's chapter_rewrites folder. */
    val fileStem: String = "",
    /**
     * Generation-specific content filename inside [fileStem] (e.g. `applied-b12f.html`). Null on
     * legacy records = the pre-generation name `applied.html`. Content and metadata always commit
     * as one generation (R09).
     */
    val contentFile: String? = null,
    val cadence: RewriteCadenceSummary = RewriteCadenceSummary(),
)

/** A generated-but-unapplied draft awaiting preview; never resolved by Reader or TTS. */
data class ChapterRewriteDraftRecord(
    val storyId: String = "",
    val chapterId: String = "",
    val chapterTitle: String = "",
    val sourceSha256: String = "",
    val createdAt: Long = 0,
    val model: String = "",
    val verifierModel: String = "",
    val promptVersion: String = "",
    val strength: String = RewriteStrength.LIGHT.wire,
    val operationId: String = "",
    val costUsd: String? = null,
    /** "ready" (preview + appliable) | "blocked" (verifier blockers) | "verify_failed". */
    val status: String = "ready",
    val verification: RewriteVerificationSummary = RewriteVerificationSummary(),
    val validationWarnings: List<String> = emptyList(),
    val mergedBlocks: Int = 0,
    /** Which provider routing tier produced the rewrite: "strict" | "relaxed" | "none". */
    val providerTier: String = "strict",
    val fileStem: String = "",
    /** Generation-specific draft filename inside [fileStem]; null = legacy `draft.html` (R09). */
    val contentFile: String? = null,
    val cadence: RewriteCadenceSummary = RewriteCadenceSummary(),
)

/** Per-story manifest document persisted at `chapter_rewrites/<safeStoryId>/manifest.json`. */
data class ChapterRewriteManifestModel(
    val format: String = "webnovel_archiver.chapter_rewrites",
    val version: Int = 1,
    val applied: Map<String, AppliedChapterRewrite> = emptyMap(),
    val drafts: Map<String, ChapterRewriteDraftRecord> = emptyMap(),
)

/** Which local content version Reader/TTS/copy resolve for a chapter. */
enum class ChapterContentVersion {
    SOURCE,
    POLISHED,
}
