package com.vinicius741.webnovelarchiver.feature.ai

import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.ai.ChapterBlockParsing
import com.vinicius741.webnovelarchiver.data.repository.appliedChapterRewrite
import com.vinicius741.webnovelarchiver.data.repository.appliedRewritePreviewHtml
import com.vinicius741.webnovelarchiver.data.repository.applyChapterRewrite
import com.vinicius741.webnovelarchiver.data.repository.chapterRewriteManifest
import com.vinicius741.webnovelarchiver.data.repository.discardChapterRewriteDraft
import com.vinicius741.webnovelarchiver.data.repository.draftRewriteHtml
import com.vinicius741.webnovelarchiver.domain.model.ChapterRewriteDraftRecord
import com.vinicius741.webnovelarchiver.domain.model.RewriteStrength
import com.vinicius741.webnovelarchiver.feature.details.showDetails
import com.vinicius741.webnovelarchiver.feature.reader.ChapterHtmlSanitizer
import com.vinicius741.webnovelarchiver.feature.reader.ReaderContentRenderer
import com.vinicius741.webnovelarchiver.feature.reader.readerDocumentColors
import com.vinicius741.webnovelarchiver.feature.reader.restartTtsForChapterVariant
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.platform.WebViewSafety
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.button
import com.vinicius741.webnovelarchiver.ui.card
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.fullButton
import com.vinicius741.webnovelarchiver.ui.makeBadge
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.row
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.spacer
import com.vinicius741.webnovelarchiver.ui.text
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.launch

/*
 * The Chapter polish comparison screen (plan §07): deterministic cadence summary, verifier
 * findings, Source/Polished tabs over the chapter HTML, and Apply / Discard / Regenerate.
 * Nothing is applied until the user taps Apply; the source chapter file is never touched.
 */

internal fun ScreenHost.showChapterRewritePreview(
    storyId: String,
    chapterId: String,
) {
    val story = repository.story(storyId) ?: return showDetails(storyId)
    val chapter = story.chapters.firstOrNull { it.id == chapterId } ?: return showDetails(storyId)
    screen(
        route = AppRoute.ChapterRewritePreview(story.id, chapter.id),
        title = "Chapter Polish",
        subtitle = chapter.title,
        onBack = { showAiControls(story.id) },
        scrollable = true,
    ) {
        text("Loading comparison…", Type.BODY_MEDIUM, ThemeManager.colors.onSurfaceVariant)
    }
    screenObserver =
        scope.launch {
            val sourceHtml = repository.readChapter(chapter) ?: chapter.content
            val manifest = repository.chapterRewriteManifest(story.id)
            val draft = manifest.drafts[chapter.id]
            val applied = repository.appliedChapterRewrite(story.id, chapter.id)
            val polishedHtml =
                draft?.let { repository.draftRewriteHtml(story.id, chapter.id) }
                    ?: repository.appliedRewritePreviewHtml(story.id, chapter.id)
            val expectedSha: String? = draft?.sourceSha256 ?: applied?.sourceSha256
            val stale: Boolean =
                sourceHtml?.let { source ->
                    ChapterBlockParsing.parseChapter(source).sourceSha256 != expectedSha
                } ?: false
            if (polishedHtml == null) {
                toast("No polished version for this chapter yet")
                return@launch showAiControls(story.id)
            }
            renderChapterRewritePreview(
                chapterTitle = chapter.title,
                storyId = story.id,
                chapterId = chapter.id,
                sourceHtml = sourceHtml.orEmpty(),
                polishedHtml = polishedHtml,
                draft = draft,
                applied = applied,
                stale = stale,
            )
        }
    rerender = { showChapterRewritePreview(storyId, chapterId) }
}

private fun ScreenHost.renderChapterRewritePreview(
    chapterTitle: String,
    storyId: String,
    chapterId: String,
    sourceHtml: String,
    polishedHtml: String,
    draft: ChapterRewriteDraftRecord?,
    applied: com.vinicius741.webnovelarchiver.domain.model.AppliedChapterRewrite?,
    stale: Boolean,
) {
    screen(
        route = AppRoute.ChapterRewritePreview(storyId, chapterId),
        title = "Chapter Polish",
        subtitle = chapterTitle,
        onBack = { showAiControls(storyId) },
        scrollable = true,
    ) {
        // The draft card set doubles as the applied view: provenance, cadence, and findings
        // explain the version the reader is using, not only the pending preview.
        val displayRecord = draft ?: applied?.toDisplayRecord()
        displayRecord?.let { addRewriteStatusCard(this, it, stale, applied = applied != null) }
        displayRecord?.let { addCadenceCard(this, it.cadence) }
        displayRecord?.let { addFindingsCard(this, it.verification, it.validationWarnings) }
        addVersionTabs(this, chapterTitle, sourceHtml, polishedHtml)
        addRewriteActions(this, storyId, chapterId, draft)
    }
    rerender = { showChapterRewritePreview(storyId, chapterId) }
}

/** The applied record rendered through the draft-card shape (same provenance fields). */
private fun com.vinicius741.webnovelarchiver.domain.model.AppliedChapterRewrite.toDisplayRecord(): ChapterRewriteDraftRecord =
    ChapterRewriteDraftRecord(
        storyId = storyId,
        chapterId = chapterId,
        chapterTitle = chapterTitle,
        sourceSha256 = sourceSha256,
        createdAt = createdAt,
        model = model,
        verifierModel = verifierModel,
        promptVersion = promptVersion,
        strength = strength,
        operationId = operationId,
        costUsd = costUsd,
        status = "ready",
        verification = verification,
        mergedBlocks = mergedBlocks,
        providerTier = providerTier,
        cadence = cadence,
    )

private fun ScreenHost.addRewriteStatusCard(
    container: LinearLayout,
    record: ChapterRewriteDraftRecord,
    stale: Boolean,
    applied: Boolean,
) {
    val colors = ThemeManager.colors
    val cardView =
        container.card {
            val (badgeText, badgeBg, badgeFg) =
                when (record.status) {
                    "ready" ->
                        Triple(
                            if (applied) "Applied · verified" else "Draft ready · verified",
                            colors.tertiaryContainer,
                            colors.onTertiaryContainer,
                        )
                    "blocked" -> Triple("Draft flagged by verifier", colors.errorContainer, colors.onErrorContainer)
                    else -> Triple("Verification failed", colors.errorContainer, colors.onErrorContainer)
                }
            addView(
                makeBadge(context, badgeText, badgeBg, badgeFg),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(Space.SM)
                },
            )
            text(
                "${record.model} → verified by ${record.verifierModel} · prompt ${record.promptVersion} · " +
                    "${RewriteStrength.fromWire(record.strength)?.label ?: record.strength} · ${record.mergedBlocks} merges" +
                    (record.costUsd?.let { " · $$it" } ?: ""),
                Type.BODY_SMALL,
                colors.onSurfaceVariant,
            )
            if (stale) {
                spacer(Space.XS)
                text(
                    "Out of date — the downloaded source changed after this rewrite was generated. " +
                        "The polished version still shows; regenerate to polish the new source.",
                    Type.BODY_SMALL,
                    colors.onErrorContainer,
                )
            }
        }
    container.addView(cardView)
}

private fun ScreenHost.addCadenceCard(
    container: LinearLayout,
    cadence: com.vinicius741.webnovelarchiver.domain.model.RewriteCadenceSummary,
) {
    val colors = ThemeManager.colors
    val cardView =
        container.card {
            text("Cadence report (deterministic)", Type.TITLE_SMALL, colors.onSurface)
            spacer(Space.XS)
            text(
                "Fragment share ${(cadence.fragmentShareBefore * 100).toInt()}% → ${(cadence.fragmentShareAfter * 100).toInt()}% · " +
                    "clusters ${cadence.clusterCountBefore} → ${cadence.clusterCountAfter} · " +
                    "triplets ${cadence.tripletCountBefore} → ${cadence.tripletCountAfter} · " +
                    "sentence CV ${cadence.sentenceLengthCvBefore} → ${cadence.sentenceLengthCvAfter}",
                Type.BODY_SMALL,
                colors.onSurfaceVariant,
            )
            if (cadence.templateSwapWarning) {
                spacer(Space.XS)
                text("⚠ ${cadence.templateSwapDetail}", Type.BODY_SMALL, colors.onErrorContainer)
            }
        }
    container.addView(cardView)
}

private fun ScreenHost.addFindingsCard(
    container: LinearLayout,
    verification: com.vinicius741.webnovelarchiver.domain.model.RewriteVerificationSummary,
    validationWarnings: List<String>,
) {
    val colors = ThemeManager.colors
    val cardView =
        container.card {
            val title =
                when (verification.status) {
                    "verified" -> "Verifier: no blockers"
                    "blocked" -> "Verifier: ${verification.blockerCount} blocker(s)"
                    else -> "Verifier: could not be read (treated as failed)"
                }
            text(title, Type.TITLE_SMALL, colors.onSurface)
            verification.findings.forEach { finding ->
                spacer(Space.XS)
                text(
                    "[${finding.severity}] ${finding.type} ${finding.blockIds.joinToString(",")}\n${finding.evidence}",
                    Type.BODY_SMALL,
                    if (finding.severity == "blocker") colors.onErrorContainer else colors.onSurfaceVariant,
                )
            }
            if (verification.status == "verify_failed") {
                spacer(Space.XS)
                text(
                    "The verifier reply was unparseable, so this draft can never be applied. Regenerate.",
                    Type.BODY_SMALL,
                    colors.onErrorContainer,
                )
            }
            validationWarnings.takeIf { it.isNotEmpty() }?.let { warnings ->
                spacer(Space.SM)
                text("Validation warnings:", Type.TITLE_SMALL, colors.onSurface)
                warnings.take(6).forEach { warning ->
                    spacer(Space.XS)
                    text("• $warning", Type.BODY_SMALL, colors.onSurfaceVariant)
                }
            }
        }
    container.addView(cardView)
}

/** Source | Polished tabs over a WebView; each version renders sanitized through the reader pipeline. */
private fun ScreenHost.addVersionTabs(
    container: LinearLayout,
    chapterTitle: String,
    sourceHtml: String,
    polishedHtml: String,
) {
    val colors = ThemeManager.colors
    var webView: WebView? = null
    val cardView =
        container.card {
            fun documentHtml(html: String): String {
                val sanitized = ChapterHtmlSanitizer.sanitize(html)
                return ReaderContentRenderer.document(
                    chapterTitle,
                    sanitized,
                    1.0f,
                    readerDocumentColors(false),
                    includeTtsScript = false,
                )
            }

            val documents = listOf(documentHtml(sourceHtml), documentHtml(polishedHtml))

            fun render(position: Int) {
                webView?.loadDataWithBaseURL(null, documents[position], "text/html", "utf-8", null)
            }

            val tabRow =
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                }
            // makeButton (unattached factory): the tab row adds these itself.
            val sourceButton =
                com.vinicius741.webnovelarchiver.ui
                    .makeButton(context, "Source", Btn.TEXT) { render(0) }
            val polishedButton =
                com.vinicius741.webnovelarchiver.ui
                    .makeButton(context, "Polished", Btn.TEXT) { render(1) }
            tabRow.addView(
                sourceButton,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            tabRow.addView(
                polishedButton,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(tabRow)
            val reader =
                WebView(context).apply {
                    WebViewSafety.applyReaderSettings(this, enableTtsHighlight = false)
                    setBackgroundColor(colors.surface)
                }
            addView(
                reader,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(420)).apply {
                    topMargin = dp(Space.SM)
                },
            )
            webView = reader
            render(0)
        }
    container.addView(cardView)
}

private fun ScreenHost.addRewriteActions(
    container: LinearLayout,
    storyId: String,
    chapterId: String,
    draft: ChapterRewriteDraftRecord?,
) {
    val story = repository.story(storyId) ?: return
    val chapter = story.chapters.firstOrNull { it.id == chapterId } ?: return
    if (draft == null) {
        // Applied view: switching and regeneration.
        container.fullButton(
            label = "Regenerate Polished Version",
            variant = Btn.FILLED,
            icon = R.drawable.wna_auto_awesome,
            bottomMarginDp = 8,
        ) { confirmChapterPolish(story, chapter) }
        return
    }
    val appliable = draft.status == "ready"
    container.fullButton(
        label = if (appliable) "Apply Polished Version" else "Apply disabled — ${draft.status}",
        variant = Btn.FILLED,
        icon = R.drawable.wna_check,
        enabled = appliable,
        bottomMarginDp = 8,
    ) {
        confirm(
            "Apply the polished version? Reader and TTS will use it for \"${chapter.title}\"; " +
                "the source chapter file is never modified and you can switch back at any time.",
            confirmLabel = "Apply",
        ) {
            scope.launch {
                repository.applyChapterRewrite(storyId, chapterId)?.let {
                    // The active variant just changed under any live/paused narration for this
                    // chapter; restart or clear it exactly like the reader's version switch.
                    restartTtsForChapterVariant(storyId, chapterId)
                    toast("Polished version applied")
                } ?: toast("Could not apply the polished version")
                showChapterRewritePreview(storyId, chapterId)
            }
        }
    }
    container.row {
        button("Discard draft", Btn.TEXT) {
            confirm("Discard this polished draft? The generation cost is already spent.", confirmLabel = "Discard") {
                scope.launch {
                    repository.discardChapterRewriteDraft(storyId, chapterId)
                    toast("Draft discarded")
                    showAiControls(storyId)
                }
            }
        }
        button("Regenerate", Btn.TEXT) { confirmChapterPolish(story, chapter) }
    }
}
