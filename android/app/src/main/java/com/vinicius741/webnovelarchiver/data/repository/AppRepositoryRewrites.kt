package com.vinicius741.webnovelarchiver.data.repository

import com.vinicius741.webnovelarchiver.ai.AiChapterRewriteDraftOutput
import com.vinicius741.webnovelarchiver.domain.model.AppliedChapterRewrite
import com.vinicius741.webnovelarchiver.domain.model.ChapterRewriteDraftRecord
import com.vinicius741.webnovelarchiver.domain.model.ChapterRewriteManifestModel
import com.vinicius741.webnovelarchiver.domain.model.RewriteCadenceSummary
import com.vinicius741.webnovelarchiver.domain.model.RewriteStrength
import com.vinicius741.webnovelarchiver.domain.model.RewriteVerificationFinding
import com.vinicius741.webnovelarchiver.domain.model.RewriteVerificationSummary

/*
 * Chapter-rewrite storage transactions, split out of [AppRepository] to keep that class inside
 * the file-size budget. They ride the same storage monitor as every other mutation: the manifest
 * is only ever read-modify-written under the repository's storageTransaction lock.
 */

/**
 * Saves a finished rewrite draft. False when the story no longer exists: the existence check rides
 * the same transaction as the save (R05), closing the deleted-mid-run check/write race instead of
 * recreating rewrite state for a story [AppStorage.deleteStory] already cleaned.
 */
internal suspend fun AppRepository.saveChapterRewriteDraft(output: AiChapterRewriteDraftOutput): Boolean =
    storageTransaction {
        if (storage.getStory(output.storyId) == null) {
            false
        } else {
            storage.chapterRewrites.saveDraft(output.storyId, output.toDraftRecord(), output.polishedHtml)
            true
        }
    }

internal suspend fun AppRepository.applyChapterRewrite(
    storyId: String,
    chapterId: String,
): AppliedChapterRewrite? =
    storageTransaction {
        storage.chapterRewrites.applyDraft(storyId, chapterId)
    }.also {
        if (it != null) republishLibrarySnapshot()
    }

internal suspend fun AppRepository.discardChapterRewriteDraft(
    storyId: String,
    chapterId: String,
) {
    storageTransaction { storage.chapterRewrites.discardDraft(storyId, chapterId) }
}

/** Removes the chapter's rewrite entirely (draft, applied file, and records). */
internal suspend fun AppRepository.removeChapterRewrite(
    storyId: String,
    chapterId: String,
) {
    storageTransaction { storage.chapterRewrites.removeRewrite(storyId, chapterId) }
    republishLibrarySnapshot()
}

/**
 * Flips which local variant the content resolver serves for this chapter, then republishes so
 * screens observing library state reload the chapter with the new text.
 */
internal suspend fun AppRepository.setChapterRewriteActive(
    storyId: String,
    chapterId: String,
    active: Boolean,
): AppliedChapterRewrite? =
    storageTransaction {
        storage.chapterRewrites.setActive(storyId, chapterId, active)
    }.also {
        if (it != null) republishLibrarySnapshot()
    }

internal suspend fun AppRepository.setChapterRewriteStrength(
    storyId: String,
    strength: RewriteStrength?,
) {
    updateStory(storyId) { latest ->
        latest?.copy(chapterRewriteStrength = strength?.wire)
    }
}

internal fun AppRepository.chapterRewriteManifest(storyId: String): ChapterRewriteManifestModel = storage.chapterRewrites.manifest(storyId)

internal fun AppRepository.appliedChapterRewrite(
    storyId: String,
    chapterId: String,
): AppliedChapterRewrite? = storage.chapterRewrites.appliedRecord(storyId, chapterId)

/** The chapter's active applied polished HTML, or null when the rewrite is absent or inactive. */
internal fun AppRepository.appliedRewriteHtml(
    storyId: String,
    chapterId: String,
): String? = storage.chapterRewrites.appliedHtml(storyId, chapterId)

/**
 * HTML read from an already-selected record (R26): callers that resolved the record from one
 * manifest snapshot must not trigger a second manifest lookup just to read the file.
 */
internal fun AppRepository.appliedRewriteHtmlForRecord(record: AppliedChapterRewrite): String? =
    storage.chapterRewrites.appliedHtmlForRecord(record)

/** The chapter's pending preview draft HTML, or null when there is none. */
internal fun AppRepository.draftRewriteHtml(
    storyId: String,
    chapterId: String,
): String? = storage.chapterRewrites.draftHtml(storyId, chapterId)

/** The applied file even when the rewrite is inactive — the compare screen's read path. */
internal fun AppRepository.appliedRewritePreviewHtml(
    storyId: String,
    chapterId: String,
): String? =
    storage.chapterRewrites.appliedRecord(storyId, chapterId)?.let { record ->
        storage.chapterRewrites.appliedHtmlForRecord(record)
    }
