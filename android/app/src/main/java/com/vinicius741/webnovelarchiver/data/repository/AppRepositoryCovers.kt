package com.vinicius741.webnovelarchiver.data.repository

import com.vinicius741.webnovelarchiver.ai.AiCoverPlanning
import com.vinicius741.webnovelarchiver.domain.model.Story
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

/** The story's locally generated cover file, when one is recorded and present on disk. */
internal fun AppRepository.coverFile(story: Story): File? = storage.resolveAbsolutePath(story.aiCoverPath)
