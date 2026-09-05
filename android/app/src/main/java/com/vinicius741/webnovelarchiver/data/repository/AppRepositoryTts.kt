package com.vinicius741.webnovelarchiver.data.repository

import com.vinicius741.webnovelarchiver.domain.model.TtsSession
import com.vinicius741.webnovelarchiver.domain.model.TtsStoryPosition

/*
 * TTS session + per-story resume-position transactions, split out of [AppRepository] to keep that
 * class inside its file-size budget. Same storage monitor as every other mutation.
 */

/** The persisted active playback session, or null when playback fully stopped. */
fun AppRepository.getTtsSession(): TtsSession? = ttsSession?.copy()

suspend fun AppRepository.saveTtsSession(session: TtsSession) {
    storageTransaction {
        storage.saveTtsSession(session)
        ttsSession = session.copy()
    }
}

suspend fun AppRepository.clearTtsSession() {
    storageTransaction {
        storage.clearTtsSession()
        ttsSession = null
    }
}

suspend fun AppRepository.getTtsStoryPosition(storyId: String): TtsStoryPosition? =
    storageTransaction { storage.getTtsStoryPositions()[storyId] }?.copy()

suspend fun AppRepository.saveTtsStoryPosition(position: TtsStoryPosition) {
    storageTransaction { storage.saveTtsStoryPosition(position) }
}

suspend fun AppRepository.clearTtsStoryPosition(storyId: String) {
    storageTransaction { storage.clearTtsStoryPosition(storyId) }
}
