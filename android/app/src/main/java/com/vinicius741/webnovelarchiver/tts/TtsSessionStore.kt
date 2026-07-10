package com.vinicius741.webnovelarchiver.tts

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

/** Serial, debounced persistence for high-frequency TTS chunk-position updates. */
internal class TtsSessionStore(
    private val persistence: TtsSessionPersistence,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val debounceMs: Long = 250L,
) {
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
    fun save(session: TtsSession)

    fun clear()
}

private class StorageTtsSessionPersistence(
    private val storage: AppStorage,
) : TtsSessionPersistence {
    override fun save(session: TtsSession) = storage.saveTtsSession(session)

    override fun clear() {
        storage.clearTtsSession()
    }
}
