package com.vinicius741.webnovelarchiver.data.repository

import com.vinicius741.webnovelarchiver.domain.model.CharacterPortrait
import com.vinicius741.webnovelarchiver.domain.model.StoryCharacter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// Characters tab persistence (CharacterStore), off the main thread.

internal suspend fun AppRepository.characterRoster(storyId: String): List<StoryCharacter>? =
    withContext(Dispatchers.IO) { storage.characters.roster(storyId) }

/** Saves under the storage lock unless the novel was deleted, so a deletion never leaves files behind. */
internal suspend fun AppRepository.saveCharacterRosterIfStoryExists(
    storyId: String,
    characters: List<StoryCharacter>,
): Boolean =
    storageTransaction {
        containsStoryLocked(storyId).also { exists -> if (exists) storage.characters.saveRoster(storyId, characters) }
    }

internal suspend fun AppRepository.characterPortraits(
    storyId: String,
    characterId: String,
): List<CharacterPortrait> = withContext(Dispatchers.IO) { storage.characters.portraits(storyId, characterId) }

internal suspend fun AppRepository.latestCharacterPortraits(storyId: String): Map<String, File> =
    withContext(Dispatchers.IO) { storage.characters.latestPortraits(storyId) }

/** Saves under the storage lock unless the novel was deleted; null when it was. */
internal suspend fun AppRepository.saveCharacterPortraitIfStoryExists(
    storyId: String,
    characterId: String,
    prompt: String,
    bytes: ByteArray,
    mediaType: String?,
): String? =
    storageTransaction {
        if (!containsStoryLocked(storyId)) null else storage.characters.savePortrait(storyId, characterId, prompt, bytes, mediaType)
    }

internal suspend fun AppRepository.deleteCharacterPortrait(
    storyId: String,
    characterId: String,
    portraitId: String,
) = withContext(Dispatchers.IO) { storage.characters.deletePortrait(storyId, characterId, portraitId) }
