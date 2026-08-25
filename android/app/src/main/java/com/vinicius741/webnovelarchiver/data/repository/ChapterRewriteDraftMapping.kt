package com.vinicius741.webnovelarchiver.data.repository

import com.vinicius741.webnovelarchiver.ai.AiChapterRewriteDraftOutput
import com.vinicius741.webnovelarchiver.ai.AiChapterRewritePrompts
import com.vinicius741.webnovelarchiver.ai.CadenceComparison
import com.vinicius741.webnovelarchiver.ai.ChapterRewriteValidation.VerifierVerdict
import com.vinicius741.webnovelarchiver.domain.model.ChapterRewriteDraftRecord
import com.vinicius741.webnovelarchiver.domain.model.RewriteCadenceSummary
import com.vinicius741.webnovelarchiver.domain.model.RewriteVerificationFinding
import com.vinicius741.webnovelarchiver.domain.model.RewriteVerificationSummary

/*
 * Draft-output → persisted-record mapping, split out of [AppRepositoryRewrites] to keep that file
 * inside detekt's function-count budget.
 */

internal fun AiChapterRewriteDraftOutput.toDraftRecord(): ChapterRewriteDraftRecord =
    ChapterRewriteDraftRecord(
        storyId = storyId,
        chapterId = chapterId,
        chapterTitle = chapterTitle,
        sourceSha256 = sourceSha256,
        createdAt = System.currentTimeMillis(),
        model = model,
        verifierModel = verifierModel,
        promptVersion = promptVersion,
        strength = strengthWire,
        operationId = operationId,
        costUsd = costUsd,
        status = status,
        verification = verdict.toSummary(verifierModel),
        validationWarnings =
            validation.warnings.map { "${it.code}: ${it.detail}" } +
                cadenceComparison.let { if (it.templateSwapWarning) listOf(it.templateSwapDetail) else emptyList() },
        mergedBlocks = validation.mergedCount,
        providerTier = providerTier,
        cadence = cadenceComparison.toSummary(),
    )

private fun com.vinicius741.webnovelarchiver.ai.ChapterRewriteValidation.VerifierVerdict?.toSummary(
    verifierModel: String,
): RewriteVerificationSummary =
    when (this) {
        null ->
            RewriteVerificationSummary(
                status = "verify_failed",
                verifierModel = verifierModel,
            )
        else ->
            RewriteVerificationSummary(
                status =
                    if (parseError !=
                        null
                    ) {
                        "verify_failed"
                    } else if (findings.any { it.severity == "blocker" }) {
                        "blocked"
                    } else {
                        "verified"
                    },
                blockerCount = findings.count { it.severity == "blocker" },
                findings =
                    findings.map { finding ->
                        RewriteVerificationFinding(
                            severity = finding.severity,
                            type = finding.type,
                            blockIds = finding.blockIds,
                            evidence = finding.evidence,
                        )
                    },
                verifierModel = verifierModel,
                verifierPromptVersion = com.vinicius741.webnovelarchiver.ai.AiChapterRewritePrompts.VERIFIER_VERSION,
            )
    }

private fun com.vinicius741.webnovelarchiver.ai.CadenceComparison.toSummary(): RewriteCadenceSummary =
    RewriteCadenceSummary(
        fragmentShareBefore = before.fragmentShare,
        fragmentShareAfter = after.fragmentShare,
        clusterCountBefore = before.clusterCount,
        clusterCountAfter = after.clusterCount,
        tripletCountBefore = before.tripletCount,
        tripletCountAfter = after.tripletCount,
        sentenceLengthCvBefore = before.sentenceLengthCv,
        sentenceLengthCvAfter = after.sentenceLengthCv,
        templateSwapWarning = templateSwapWarning,
        templateSwapDetail = templateSwapDetail,
    )
