package com.vinicius741.webnovelarchiver.tts

import com.vinicius741.webnovelarchiver.data.repository.AppRepository
import com.vinicius741.webnovelarchiver.domain.model.TtsSession
import com.vinicius741.webnovelarchiver.domain.model.TtsStoryPosition
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Serial, debounced persistence for high-frequency TTS chunk-position updates.
 *
 * Every session write also mirrors the story's per-story position ([TtsSessionPlanning.storyPosition])
 * so an explicit stop can clear the active session while the story still remembers where it stopped.
 *
 * The persistence target must be the single owner of TTS session state so the read path (the
 * repository's in-memory cache, read by [TtsPlaybackPreparer.resume] and the reader/settings resume
 * affordances) and this write path never diverge. Construct with the [AppRepository] in production;
 * the [TtsSessionPersistence] constructor remains the test seam.
 */
internal class TtsSessionStore(
    private val persistence: TtsSessionPersistence,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val debounceMs: Long = 250L,
) {
    constructor(
        repository: AppRepository,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        debounceMs: Long = 250L,
    ) : this(RepositoryTtsSessionPersistence(repository), dispatcher, debounceMs)

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val writeMutex = Mutex()
    private val schedulingLock = Any()
    private var pendingWrite: Job? = null

    fun schedule(session: TtsSession) {
        val snapshot = session.copy()
        synchronized(schedulingLock) {
            pendingWrite?.cancel()
            pendingWrite =
                scope.launch {
                    delay(debounceMs)
                    runCatching { writeMutex.withLock { writeSessionAndPosition(snapshot) } }
                        .onFailure { Timber.e(it, "TTS position persistence failed") }
                }
        }
    }

    suspend fun flush(session: TtsSession) {
        cancelPending()
        withContext(dispatcher) { writeMutex.withLock { writeSessionAndPosition(session.copy()) } }
    }

    /**
     * Explicit stop: keep the story position (already mirrored), drop the active session.
     * [persistPosition] false forgets it instead — chunk indices remap across content variants,
     * so a variant switch must not leave a stale mid-paragraph position behind.
     */
    suspend fun stop(
        lastSession: TtsSession?,
        persistPosition: Boolean = true,
        forgetStoryId: String? = null,
    ) {
        cancelPending()
        withContext(dispatcher) {
            writeMutex.withLock {
                val position = lastSession?.let(TtsSessionPlanning::storyPosition)
                if (position != null) {
                    if (persistPosition) persistence.savePosition(position) else persistence.clearPosition(position.storyId)
                } else if (!persistPosition && forgetStoryId != null) {
                    persistence.clearPosition(forgetStoryId)
                }
                persistence.clear()
            }
        }
    }

    /** Natural completion: nothing left to resume, so the story position goes too. Description sessions have none. */
    suspend fun finish(lastSession: TtsSession?) {
        cancelPending()
        withContext(dispatcher) {
            writeMutex.withLock {
                persistence.clear()
                lastSession?.let(TtsSessionPlanning::storyPosition)?.let { persistence.clearPosition(it.storyId) }
            }
        }
    }

    private suspend fun writeSessionAndPosition(session: TtsSession) {
        persistence.save(session)
        TtsSessionPlanning.storyPosition(session)?.let { persistence.savePosition(it) }
    }

    private fun cancelPending() {
        synchronized(schedulingLock) {
            pendingWrite?.cancel()
            pendingWrite = null
        }
    }
}

internal interface TtsSessionPersistence {
    suspend fun save(session: TtsSession)

    suspend fun savePosition(position: TtsStoryPosition)

    suspend fun clear()

    suspend fun clearPosition(storyId: String)
}

/**
 * Production persistence target. Routes writes through the repository so the in-memory session cache
 * and the on-disk JSON stay coherent in a single call — the repository updates both under its
 * transaction lock (Reliability R2). Without this, a pause flushed here would update only disk while
 * [com.vinicius741.webnovelarchiver.tts.TtsPlaybackPreparer.resume] reads the still-stale cache and
 * resume silently no-ops.
 */
private class RepositoryTtsSessionPersistence(
    private val repository: AppRepository,
) : TtsSessionPersistence {
    override suspend fun save(session: TtsSession) = repository.saveTtsSession(session)

    override suspend fun savePosition(position: TtsStoryPosition) = repository.saveTtsStoryPosition(position)

    override suspend fun clear() {
        repository.clearTtsSession()
    }

    override suspend fun clearPosition(storyId: String) {
        repository.clearTtsStoryPosition(storyId)
    }
}
