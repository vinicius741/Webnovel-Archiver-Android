package com.vinicius741.webnovelarchiver.data.repository

import com.vinicius741.webnovelarchiver.ai.AiCoverDraft
import com.vinicius741.webnovelarchiver.ai.AiCoverPlanning
import com.vinicius741.webnovelarchiver.data.storage.AiCoverDraftRecord
import com.vinicius741.webnovelarchiver.domain.model.Story
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/*
 * Cover-art storage transactions, split out of [AppRepository] to keep that class inside the
 * file-size budget. They ride the same updateStory monitor as every other story mutation.
 */

/**
 * Writes a generated cover image to storage and points the story at it. The source
 * [Story.coverUrl] is never touched, so [clearAiCover] can restore it at any time.
 */
internal suspend fun AppRepository.setAiCover(
    storyId: String,
    bytes: ByteArray,
    mediaType: String?,
): Story? =
    updateStory(storyId) { latest ->
        latest?.let { story ->
            val file = storage.coverFiles.save(storyId, bytes, AiCoverPlanning.coverFileExtension(mediaType))
            StoryMutations.setAiCoverPath(story, storage.relativize(file))
        }
    }

/** Removes the generated cover file and record so the story shows its source cover again. */
internal suspend fun AppRepository.clearAiCover(storyId: String): Story? =
    updateStory(storyId) { latest ->
        latest?.let { story ->
            storage.coverFiles.delete(storyId)
            StoryMutations.clearAiCover(story)
        }
    }

/** Flips which cover the app displays (AI vs source) without touching either stored image. */
internal suspend fun AppRepository.setShowAiCover(
    storyId: String,
    showAi: Boolean,
): Story? =
    updateStory(storyId) { latest ->
        latest?.let { StoryMutations.setShowAiCover(it, showAi) }
    }

/** The story's locally generated cover file, when one is recorded and present on disk. */
internal fun AppRepository.coverFile(story: Story): File? = storage.resolveAbsolutePath(story.aiCoverPath)

/*
 * Pending (preview-only) AI cover drafts. Unlike the cover transactions above these do not mutate
 * library state — they persist the background generation result so it survives navigation and
 * process death until the user applies or discards it.
 */

/** Persists the staged flow's editable image prompt (stage 1 result), dropping any painted preview. */
internal suspend fun AppRepository.saveAiCoverPromptDraft(
    storyId: String,
    prompt: String,
) {
    withContext(Dispatchers.IO) { storage.aiCoverDrafts.savePrompt(storyId, prompt) }
}

/** Persists a painted preview (with the prompt that produced it) as the story's pending draft. */
internal suspend fun AppRepository.saveAiCoverImageDraft(
    storyId: String,
    draft: AiCoverDraft,
) {
    withContext(Dispatchers.IO) { storage.aiCoverDrafts.saveImage(storyId, draft) }
}

/**
 * Persists a finished cover draft only when the story still exists (R05): the existence check and
 * the draft save run as one storage transaction, so a story deleted mid-generation cannot regain
 * orphaned draft files. False = discarded.
 */
internal suspend fun AppRepository.persistAiCoverDraftIfStoryExists(
    storyId: String,
    record: AiCoverDraftRecord,
): Boolean =
    storageTransaction {
        if (storage.getStory(storyId) == null) {
            false
        } else {
            when (record) {
                is AiCoverDraftRecord.PromptOnly -> storage.aiCoverDrafts.savePrompt(storyId, record.prompt)
                is AiCoverDraftRecord.Image -> storage.aiCoverDrafts.saveImage(storyId, record.draft)
            }
            true
        }
    }

/** The story's persisted pending draft, or null when there is none. */
internal suspend fun AppRepository.loadAiCoverDraft(storyId: String): AiCoverDraftRecord? =
    withContext(Dispatchers.IO) { storage.aiCoverDrafts.load(storyId) }

/** Deletes the story's pending draft; called on Apply, Discard, and AI-cover deletion. */
internal suspend fun AppRepository.deleteAiCoverDraft(storyId: String) {
    withContext(Dispatchers.IO) { storage.aiCoverDrafts.delete(storyId) }
}
