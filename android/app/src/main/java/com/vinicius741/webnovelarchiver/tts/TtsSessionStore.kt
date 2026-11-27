package com.vinicius741.webnovelarchiver.tts

import com.vinicius741.webnovelarchiver.data.repository.AppRepository
import com.vinicius741.webnovelarchiver.data.repository.clearTtsSession
import com.vinicius741.webnovelarchiver.data.repository.clearTtsStoryPosition
import com.vinicius741.webnovelarchiver.data.repository.saveTtsSession
import com.vinicius741.webnovelarchiver.data.repository.saveTtsStoryPosition
import com.vinicius741.webnovelarchiver.domain.model.TtsSession
import com.vinicius741.webnovelarchiver.domain.model.TtsStoryPosition
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
 * Write cadence: a change the listener made (story, chapter, pause, speed, voice, a jump, a rewind) is
 * written [debounceMs] after it settles. Plain forward progress through a playing chapter
 * ([TtsSessionPlanning.isProgressOnly]) is written at most once per [progressIntervalMs], always
 * ending with the latest place. Pause, stop and finish write at once ([flush], [stop], [finish]).
 * So when the process dies mid-playback (no pause or stop ran), resume loses at most
 * [progressIntervalMs] + [debounceMs] of listening; each saved place is the start of a sentence, as
 * before. Unwritten progress of a story that playback moves away from is still saved as that story's
 * position before the new story's session is written.
 *
 * The persistence target must be the single owner of TTS session state so the read path (the
 * repository's in-memory cache, read by [TtsPlaybackPreparer.resume] and the reader/settings resume
 * affordances) and this write path never diverge. Construct with the [AppRepository] in production;
 * the [TtsSessionPersistence] constructor remains the test seam.
 */
internal class TtsSessionStore(
    private val persistence: TtsSessionPersistence,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val debounceMs: Long = DEBOUNCE_MS,
    private val progressIntervalMs: Long = PROGRESS_INTERVAL_MS,
    /** Monotonic milliseconds; tests pass the virtual clock. */
    private val clock: () -> Long = { System.nanoTime() / NANOS_PER_MS },
) {
    constructor(
        repository: AppRepository,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        debounceMs: Long = DEBOUNCE_MS,
    ) : this(RepositoryTtsSessionPersistence(repository), dispatcher, debounceMs)

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val writeMutex = Mutex()

    // Guarded by schedulingLock.
    private val schedulingLock = Any()
    private var pendingWrite: Job? = null
    private var pendingSession: TtsSession? = null
    private val carriedPositions = LinkedHashMap<String, TtsStoryPosition>()
    private var written: TtsSession? = null
    private var writtenAt: Long? = null

    fun schedule(session: TtsSession) {
        val snapshot = session.copy()
        synchronized(schedulingLock) {
            cancelPendingLocked(snapshot.storyId)
            pendingSession = snapshot
            val wait = waitBeforeWriteLocked(snapshot)
            pendingWrite =
                scope.launch {
                    delay(wait)
                    val carried =
                        synchronized(schedulingLock) {
                            // A newer schedule replaced this one while it woke up.
                            if (pendingSession !== snapshot) return@launch
                            pendingSession = null
                            pendingWrite = null
                            takeCarriedLocked()
                        }
                    // Once started, a write completes: later writes queue behind it on the mutex.
                    withContext(NonCancellable) {
                        runCatching { writeMutex.withLock { writeSessionAndPosition(snapshot, carried) } }
                            .onFailure { Timber.e(it, "TTS position persistence failed") }
                    }
                }
        }
    }

    suspend fun flush(session: TtsSession) {
        val snapshot = session.copy()
        val carried = cancelPending(snapshot.storyId)
        withContext(dispatcher) { writeMutex.withLock { writeSessionAndPosition(snapshot, carried) } }
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
        val carried = cancelPending(lastSession?.storyId ?: forgetStoryId)
        withContext(dispatcher) {
            writeMutex.withLock {
                carried.forEach { persistence.savePosition(it) }
                val position = lastSession?.let(TtsSessionPlanning::storyPosition)
                if (position != null) {
                    if (persistPosition) persistence.savePosition(position) else persistence.clearPosition(position.storyId)
                } else if (!persistPosition && forgetStoryId != null) {
                    persistence.clearPosition(forgetStoryId)
                }
                persistence.clear()
                markWritten(null)
            }
        }
    }

    /**
     * Natural completion: the active session ends, but the story remembers it was caught up here so
     * a chapter published later continues from the right place. Description sessions have no position.
     */
    suspend fun finish(lastSession: TtsSession?) {
        val carried = cancelPending(lastSession?.storyId)
        withContext(dispatcher) {
            writeMutex.withLock {
                carried.forEach { persistence.savePosition(it) }
                persistence.clear()
                markWritten(null)
                lastSession?.let(TtsSessionPlanning::storyPosition)?.let { persistence.savePosition(it.copy(caughtUp = true)) }
            }
        }
    }

    private suspend fun writeSessionAndPosition(
        session: TtsSession,
        carried: List<TtsStoryPosition>,
    ) {
        carried.forEach { persistence.savePosition(it) }
        persistence.save(session)
        TtsSessionPlanning.storyPosition(session)?.let { persistence.savePosition(it) }
        markWritten(session)
    }

    private fun markWritten(session: TtsSession?) {
        synchronized(schedulingLock) {
            written = session
            writtenAt = session?.let { clock() }
        }
    }

    /** Debounce for a change; for plain progress, whatever is left of the interval since the last write. */
    private fun waitBeforeWriteLocked(next: TtsSession): Long {
        val last = written ?: return debounceMs
        val lastAt = writtenAt ?: return debounceMs
        if (!TtsSessionPlanning.isProgressOnly(last, next)) return debounceMs
        return (lastAt + progressIntervalMs - clock()).coerceAtLeast(debounceMs)
    }

    /** Cancels the pending write; returns the unwritten positions of other stories, to write first. */
    private fun cancelPending(nextStoryId: String?): List<TtsStoryPosition> =
        synchronized(schedulingLock) {
            cancelPendingLocked(nextStoryId)
            takeCarriedLocked()
        }

    /**
     * A pending write of the same story is superseded by the next one. A pending write of another
     * story is carried: its position is written before the next session, so switching stories never
     * drops the progress the throttle was still holding.
     */
    private fun cancelPendingLocked(nextStoryId: String?) {
        pendingWrite?.cancel()
        pendingWrite = null
        val pending = pendingSession ?: return
        pendingSession = null
        if (pending.storyId != nextStoryId) {
            TtsSessionPlanning.storyPosition(pending)?.let { carriedPositions[it.storyId] = it }
        }
    }

    private fun takeCarriedLocked(): List<TtsStoryPosition> = carriedPositions.values.toList().also { carriedPositions.clear() }

    companion object {
        const val DEBOUNCE_MS = 250L

        /** Longest stretch of plain playback progress left unsaved while listening. */
        const val PROGRESS_INTERVAL_MS = 10_000L

        private const val NANOS_PER_MS = 1_000_000L
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
