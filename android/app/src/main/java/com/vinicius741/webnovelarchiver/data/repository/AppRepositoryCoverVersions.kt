package com.vinicius741.webnovelarchiver.data.repository

import com.vinicius741.webnovelarchiver.ai.AiCoverDraft
import com.vinicius741.webnovelarchiver.ai.AiCoverPlanning
import com.vinicius741.webnovelarchiver.domain.model.Story
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Preserve covers applied by older app versions before they are overwritten. */
internal fun AppRepository.preserveAppliedCover(story: Story): String? =
    coverFile(story)?.takeIf { it.isFile }?.let { file ->
        val mediaType =
            when (file.extension.lowercase()) {
                "jpg", "jpeg" -> "image/jpeg"
                "webp" -> "image/webp"
                else -> "image/png"
            }
        storage.aiCoverDrafts.versions.save(story.id, AiCoverDraft("", file.readBytes(), mediaType))
    }

internal suspend fun AppRepository.listAiCoverVersions(storyId: String) =
    withContext(Dispatchers.IO) {
        val story = story(storyId)
        storage.aiCoverDrafts.versions.listForDisplay(
            storyId,
            story?.let { coverFile(it) },
            story?.let(AiCoverPlanning::isAiCoverActive) == true,
        )
    }
