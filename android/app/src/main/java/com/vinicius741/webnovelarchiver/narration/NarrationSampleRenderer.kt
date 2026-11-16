package com.vinicius741.webnovelarchiver.narration

import com.vinicius741.webnovelarchiver.tts.TtsPcmClip
import com.vinicius741.webnovelarchiver.tts.TtsPcmPlanning
import com.vinicius741.webnovelarchiver.tts.kokoro.KokoroVoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/** One computer the Voice & Speech sample can generate on. */
internal interface NarrationSamplePlace {
    val name: String

    /** Connects and returns how many sentences it may generate at once; throws [NarrationServerProblem]. */
    suspend fun connect(): Int

    /** See [NarrationServerClient.synthesize]. */
    suspend fun synthesize(text: String): TtsPcmClip?

    fun close()
}

/**
 * Generates the Voice & Speech sample on the user's computers before it plays, for a built-in model
 * that is not on this phone. Every chosen computer takes sentences from one shared list, so choosing
 * several finishes sooner; a computer that cannot be used leaves its sentences to the others, and once
 * the list is empty an idle computer also takes a sentence another is still on, so a slow or stuck
 * one does not hold up the sample. Clips are trimmed like a chapter's, so the pauses heard are the
 * chosen ones. Unlike chapter generation it never waits long: a sample is wanted now, so it fails
 * with the reason instead.
 */
internal class NarrationSampleRenderer(
    private val places: List<NarrationSamplePlace>,
    private val maxBusyWaitMs: Long = MAX_BUSY_WAIT_MS,
    private val idlePollMs: Long = IDLE_POLL_MS,
    private val now: () -> Long = { System.nanoTime() / NANOS_PER_MS },
    private val trim: (TtsPcmClip) -> TtsPcmClip = TtsPcmPlanning::trimSilence,
) {
    /**
     * Returns one clip per text, in order. [onProgress] gets the number finished so far and the
     * computers still generating, in order and never going backwards. Throws [NarrationServerProblem]
     * when the chosen computers could not generate them all.
     */
    suspend fun render(
        texts: List<String>,
        onProgress: (done: Int, generatingOn: List<String>) -> Unit = { _, _ -> },
    ): List<TtsPcmClip> {
        if (places.isEmpty()) throw NarrationServerProblem("No computer is chosen to generate on")
        if (texts.isEmpty()) return emptyList()
        val work = Work(texts.size, places.map { it.name }, onProgress)
        try {
            coroutineScope {
                val jobs = places.mapIndexed { id, place -> launch { runPlace(id, place, texts, work) } }
                // Once every sentence is back, a computer still on a copy of one is not waited for.
                val stopLeftovers = launch { work.finished.await().also { jobs.forEach { it.cancel() } } }
                jobs.joinAll()
                stopLeftovers.cancel()
            }
        } catch (failed: SampleFailed) {
            // Thrown by the slot that found a sentence no computer can generate; it stopped the others.
            throw failed.problem
        } finally {
            places.forEach { runCatching { it.close() } }
        }
        return work.result()
    }

    private suspend fun runPlace(
        id: Int,
        place: NarrationSamplePlace,
        texts: List<String>,
        work: Work,
    ) {
        val slots =
            try {
                place.connect().coerceAtLeast(1)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (
                @Suppress("TooGenericExceptionCaught") failure: Exception,
            ) {
                work.retire(id, asProblem(place, failure))
                return
            }
        val state = PlaceState()
        coroutineScope {
            repeat(slots) { launch { runSlot(id, place, texts, work, state) } }
        }
    }

    private suspend fun runSlot(
        id: Int,
        place: NarrationSamplePlace,
        texts: List<String>,
        work: Work,
        state: PlaceState,
    ) {
        while (!state.retired) {
            val index = work.take(id) ?: return
            if (index == WAIT) {
                delay(idlePollMs)
                continue
            }
            if (!generate(id, place, texts[index], index, work, state)) return
        }
    }

    /** Generates one sentence; false when this slot should stop. */
    @Suppress("ReturnCount") // One sentence's outcomes, each ending the attempt.
    private suspend fun generate(
        id: Int,
        place: NarrationSamplePlace,
        text: String,
        index: Int,
        work: Work,
        state: PlaceState,
    ): Boolean {
        val clip =
            try {
                place.synthesize(text)
            } catch (cancelled: CancellationException) {
                work.giveBack(index, id)
                throw cancelled
            } catch (busy: NarrationServerBusy) {
                work.giveBack(index, id)
                // Measured on the clock from the first refusal in a row, however many slots were refused.
                val since = state.busySince.updateAndGet { if (it == 0L) now() else it }
                if (busy.stopped || now() - since >= maxBusyWaitMs) {
                    retire(id, busy, work, state)
                    return false
                }
                delay(busy.retryAfterMs.coerceIn(MIN_BUSY_WAIT_MS, maxBusyWaitMs))
                return true
            } catch (
                @Suppress("TooGenericExceptionCaught") failure: Exception,
            ) {
                // A dropped connection was already retried by the place; anything unexpected still only
                // takes this computer out, never the app.
                work.giveBack(index, id)
                retire(id, asProblem(place, failure), work, state)
                return false
            }
        if (clip == null) {
            work.failedOnce(index, id)?.let { throw SampleFailed(it) }
            return true
        }
        state.busySince.set(0L)
        work.finish(index, id, trim(clip))
        return true
    }

    private suspend fun retire(
        id: Int,
        problem: NarrationServerProblem,
        work: Work,
        state: PlaceState,
    ) {
        if (state.retired) return
        state.retired = true
        work.retire(id, problem)
    }

    private fun asProblem(
        place: NarrationSamplePlace,
        failure: Exception,
    ): NarrationServerProblem =
        failure as? NarrationServerProblem
            ?: NarrationServerProblem("${place.name} could not generate the sample", transient = true, cause = failure)

    private class SampleFailed(
        val problem: NarrationServerProblem,
    ) : Exception(problem.message, problem)

    private class PlaceState {
        @Volatile
        var retired = false

        /** When this computer started refusing for memory ([now]), or 0 while it is not. */
        val busySince = AtomicLong(0L)
    }

    /** The sentences still to generate, shared by every computer. */
    private class Work(
        size: Int,
        private val names: List<String>,
        private val onProgress: (Int, List<String>) -> Unit,
    ) {
        private val lock = Mutex()
        private val pending = ArrayDeque((0 until size).toList())

        /** The computers (by place id) working on each sentence right now. */
        private val running = Array(size) { mutableSetOf<Int>() }

        /** How often each computer could not generate each sentence. */
        private val failures = Array(size) { IntArray(names.size) }
        private val live = names.indices.toMutableSet()
        private var done = 0

        /** Completes when every sentence has a clip. */
        val finished = CompletableDeferred<Unit>()
        val clips = arrayOfNulls<TtsPcmClip>(size)
        val problems = mutableListOf<Pair<String, NarrationServerProblem>>()

        private fun takeable(
            index: Int,
            id: Int,
        ) = clips[index] == null && id !in running[index] && failures[index][id] < MAX_SENTENCE_ATTEMPTS

        /**
         * The next sentence for computer [id]: a waiting one, else one another computer is still on
         * (once), else [WAIT] while those may still come back, or null when nothing is left for it.
         */
        suspend fun take(id: Int): Int? =
            lock.withLock {
                val waiting = pending.firstOrNull { takeable(it, id) }
                if (waiting != null) {
                    pending.remove(waiting)
                    running[waiting] += id
                    return@withLock waiting
                }
                val stolen = running.indices.firstOrNull { running[it].size == 1 && takeable(it, id) }
                if (stolen != null) {
                    running[stolen] += id
                    return@withLock stolen
                }
                if (running.any { it.isNotEmpty() }) WAIT else null
            }

        suspend fun finish(
            index: Int,
            id: Int,
            clip: TtsPcmClip,
        ) = lock.withLock {
            running[index] -= id
            if (clips[index] != null) return@withLock
            clips[index] = clip
            done++
            onProgress(done, liveNames())
            if (done == clips.size) finished.complete(Unit)
        }

        /** Puts [index] back for any computer to take, unless it is done or another computer is on it. */
        suspend fun giveBack(
            index: Int,
            id: Int,
        ) = lock.withLock { requeue(index, id) }

        /**
         * Computer [id] could not generate [index]. The sentence goes back, to this computer once more
         * and to the others; the problem to fail the sample with once no live computer can generate it.
         */
        suspend fun failedOnce(
            index: Int,
            id: Int,
        ): NarrationServerProblem? =
            lock.withLock {
                failures[index][id]++
                requeue(index, id)
                val exhausted = clips[index] == null && live.all { failures[index][it] >= MAX_SENTENCE_ATTEMPTS }
                if (exhausted) NarrationServerProblem("${names[id]} could not generate a sentence of the sample") else null
            }

        suspend fun retire(
            id: Int,
            problem: NarrationServerProblem,
        ) = lock.withLock {
            live -= id
            problems += names[id] to problem
            if (live.isNotEmpty()) onProgress(done, liveNames())
        }

        private fun requeue(
            index: Int,
            id: Int,
        ) {
            running[index] -= id
            if (clips[index] == null && running[index].isEmpty() && index !in pending) pending.addFirst(index)
        }

        private fun liveNames() = live.sorted().map { names[it] }

        /** Every clip in order; throws why the computers could not generate one when any is missing. */
        fun result(): List<TtsPcmClip> {
            if (clips.any { it == null }) throw NarrationServerPlanning.poolFailure(problems)
            return clips.map { checkNotNull(it) }
        }
    }

    companion object {
        /** How long one computer may keep refusing for memory before the sample gives up on it. */
        const val MAX_BUSY_WAIT_MS = 30_000L

        /** A sentence takes a computer a few seconds; one that has not answered by then is not coming. */
        const val SAMPLE_READ_TIMEOUT_MS = 45_000
        private const val MIN_BUSY_WAIT_MS = 250L
        private const val IDLE_POLL_MS = 100L
        private const val MAX_SENTENCE_ATTEMPTS = 2
        private const val NANOS_PER_MS = 1_000_000L
        private const val WAIT = -1

        /** The sample's places on [servers], speaking as [speaker]. */
        fun forServers(
            servers: List<NarrationServer>,
            speaker: KokoroVoice,
        ): NarrationSampleRenderer = NarrationSampleRenderer(servers.map { RemoteSamplePlace(it, speaker) })
    }
}

/** A computer running the built-in model, reached local network first, then Tailscale. */
private class RemoteSamplePlace(
    private val server: NarrationServer,
    private val speaker: KokoroVoice,
) : NarrationSamplePlace {
    @Volatile
    private var client: NarrationServerClient? = null
    private val rerouting = Mutex()

    override val name: String get() = server.name

    override suspend fun connect(): Int {
        val reached = reach()
        client = reached.client
        return NarrationServerPlanning.inFlight(reached.value)
    }

    override suspend fun synthesize(text: String): TtsPcmClip? {
        val spoken = speaker.model.spokenText(text)
        // Nothing left to say (only marks): the same empty clip the phone gives.
        if (spoken.isBlank()) return TtsPcmClip(SAMPLE_RATE, ByteArray(0))
        val current = client ?: throw NarrationServerProblem("Not connected to ${server.name}", transient = true)
        return try {
            current.synthesize(spoken, speaker)
        } catch (busy: NarrationServerBusy) {
            throw busy
        } catch (problem: NarrationServerProblem) {
            // A dropped connection or a Wi-Fi hiccup gets one more try, on whichever address answers now.
            if (!problem.transient) throw problem
            reroute(current).synthesize(spoken, speaker)
        }
    }

    /** Replaces [failed] with a fresh connection, once for all the sentences that failed on it. */
    private suspend fun reroute(failed: NarrationServerClient): NarrationServerClient =
        rerouting.withLock {
            client?.takeIf { it !== failed }?.let { return@withLock it }
            val reached = reach()
            client = reached.client
            failed.close()
            reached.client
        }

    private suspend fun reach() = reachCompatibleServer(server, speaker, NarrationSampleRenderer.SAMPLE_READ_TIMEOUT_MS)

    override fun close() {
        client?.close()
        client = null
    }

    private companion object {
        const val SAMPLE_RATE = 24_000
    }
}
