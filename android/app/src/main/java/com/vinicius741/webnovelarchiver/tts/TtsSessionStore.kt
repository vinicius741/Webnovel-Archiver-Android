package com.vinicius741.webnovelarchiver.tts

import com.vinicius741.webnovelarchiver.data.repository.AppRepository
import com.vinicius741.webnovelarchiver.data.storage.AppStorage
import com.vinicius741.webnovelarchiver.domain.model.TtsSession
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
 * The persistence target must be the single owner of TTS session state so the read path (the
 * repository's in-memory cache, read by [TtsPlaybackPreparer.resume] and the reader/settings resume
 * affordances) and this write path never diverge. Construct with the [AppRepository] in production;
 * the storage-only constructor is retained for tests and legacy call sites that do not hold a
 * repository.
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

    constructor(
        storage: AppStorage,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        debounceMs: Long = 250L,
    ) : this(StorageTtsSessionPersistence(storage), dispatcher, debounceMs)

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
                    runCatching { writeMutex.withLock { persistence.save(snapshot) } }
                        .onFailure { Timber.e(it, "TTS position persistence failed") }
                }
        }
    }

    suspend fun flush(session: TtsSession) {
        synchronized(schedulingLock) {
            pendingWrite?.cancel()
            pendingWrite = null
        }
        withContext(dispatcher) { writeMutex.withLock { persistence.save(session.copy()) } }
    }

    suspend fun clear() {
        synchronized(schedulingLock) {
            pendingWrite?.cancel()
            pendingWrite = null
        }
        withContext(dispatcher) { writeMutex.withLock { persistence.clear() } }
    }
}

internal interface TtsSessionPersistence {
    suspend fun save(session: TtsSession)

    suspend fun clear()
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

    override suspend fun clear() {
        repository.clearTtsSession()
    }
}

private class StorageTtsSessionPersistence(
    private val storage: AppStorage,
) : TtsSessionPersistence {
    override suspend fun save(session: TtsSession) = storage.saveTtsSession(session)

    override suspend fun clear() {
        storage.clearTtsSession()
    }
}
