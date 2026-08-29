package com.vinicius741.webnovelarchiver.ai

import com.vinicius741.webnovelarchiver.domain.model.AppliedChapterRewrite
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.ChapterRewriteDraftRecord
import java.util.Locale

/**
 * Pure counting and filtering rules for the Chapter Polish UI: the per-story summary line, the
 * browse dialog's search + status filter, and the "next unpolished" batch pick. Status tags are
 * plain strings so this stays Android-free; feature/ai maps them onto its row enum.
 */
object AiChapterPolishPlanning {
    const val STATUS_GENERATING = "generating"
    const val STATUS_QUEUED = "queued"
    const val STATUS_DRAFT_READY = "draft_ready"
    const val STATUS_DRAFT_BLOCKED = "draft_blocked"
    const val STATUS_APPLIED_ACTIVE = "applied_active"
    const val STATUS_APPLIED_INACTIVE = "applied_inactive"

    data class Summary(
        val appliedActive: Int,
        val appliedInactive: Int,
        val draftsReady: Int,
        val draftsBlocked: Int,
    ) {
        /** "3 polished · 2 drafts ready · 1 flagged"; null when there is nothing to report. */
        fun line(): String? =
            listOf(
                (appliedActive + appliedInactive).takeIf { it > 0 }?.let { "$it polished" },
                draftsReady.takeIf { it > 0 }?.let { "$it draft${if (it == 1) "" else "s"} ready" },
                draftsBlocked.takeIf { it > 0 }?.let { "$it flagged" },
            ).filterNotNull().takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    fun summarize(
        drafts: Map<String, ChapterRewriteDraftRecord>,
        applied: Map<String, AppliedChapterRewrite>,
    ): Summary =
        Summary(
            appliedActive = applied.values.count { it.active },
            appliedInactive = applied.values.count { !it.active },
            draftsReady = drafts.values.count { it.status == "ready" },
            draftsBlocked = drafts.values.count { it.status != "ready" },
        )

    /** Browse-dialog status filters; the empty filter shows everything. */
    fun matchesFilter(
        status: String?,
        filter: String,
    ): Boolean =
        when (filter) {
            "ready" -> status == STATUS_DRAFT_READY
            "flagged" -> status == STATUS_DRAFT_BLOCKED
            "polished" -> status == STATUS_APPLIED_ACTIVE || status == STATUS_APPLIED_INACTIVE
            "unpolished" -> status == null
            else -> true
        }

    /** Search matches "chapter N" plus the title, mirroring the context-chapter dialog. */
    fun filterChapters(
        chapters: List<Pair<Int, Chapter>>,
        statusOf: (String) -> String?,
        query: String,
        filter: String,
    ): List<Pair<Int, Chapter>> {
        val needle = query.trim().lowercase(Locale.US)
        return chapters.filter { (index, chapter) ->
            matchesFilter(statusOf(chapter.id), filter) &&
                (needle.isEmpty() || "chapter ${index + 1} ${chapter.title}".lowercase(Locale.US).contains(needle))
        }
    }

    /**
     * The batch target: downloaded chapters in reading order with neither a pending draft nor an
     * applied rewrite, capped at [limit].
     */
    fun nextUnpolished(
        chapters: List<Chapter>,
        statusOf: (String) -> String?,
        limit: Int,
    ): List<Chapter> = chapters.filter { it.downloaded && statusOf(it.id) == null }.take(limit)
}
