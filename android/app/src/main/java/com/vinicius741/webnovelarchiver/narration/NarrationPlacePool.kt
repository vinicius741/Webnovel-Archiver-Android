package com.vinicius741.webnovelarchiver.narration

import com.vinicius741.webnovelarchiver.tts.TtsPcmClip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.File

/**
 * Where Kokoro chapters generate: [servers] share each chapter's sentences, and with [phoneFallback]
 * this phone takes over while none of them can generate. No servers means this phone alone.
 */
internal data class NarrationPlaces(
    val servers: List<NarrationServer> = emptyList(),
    val phoneFallback: Boolean = false,
) {
    /** Changes whenever the chosen computers, their addresses or tokens, or the fallback change; null on this phone. */
    val locationKey: String?
        get() =
            if (servers.isEmpty()) {
                null
            } else {
                (servers.map { it.locationKey } + listOfNotNull(PHONE_KEY.takeIf { phoneFallback })).joinToString(",")
            }

    private companion object {
        const val PHONE_KEY = "phone"
    }
}

/** One computer in a [NarrationPlacePool]. */
internal interface NarrationPlace {
    /** The server's id, for its measured speed. */
    val id: String
    val name: String

    /** Sentences it takes at once right now. */
    val slots: Int

    /**
     * Connects (again), and with [checkMemory] also checks it is not stopped for memory. Throws
     * [NarrationServerProblem] while it cannot generate.
     */
    suspend fun connect(checkMemory: Boolean)

    /**
     * Speaks [text]; null when it could not generate this one sentence. Throws [NarrationServerBusy]
     * while it holds back for memory, and [NarrationServerProblem] when it cannot be used.
     */
    suspend fun synthesize(text: String): TtsPcmClip?

    fun close()
}

/**
 * Several computers generating one chapter together. Each sentence goes to whichever computer has a
 * free slot, so the generator sees one synthesizer that runs the sum of their sentences at once and
 * still writes clips in order.
 *
 * A computer that cannot be reached is set aside and its sentence goes to another; it is checked
 * again in the background (after 15 s, then less often) and rejoins when it answers. One short of
 * memory (`503`) is left alone for as long as it asks. Generation stops with a [NarrationServerProblem]
 * (which pauses the queue) only when every computer is out, or after [MAX_STOPPED_WAIT_MS] of them all
 * having stopped for memory; with a [phone] fallback, this phone generates instead until one is back.
 */
@Suppress("TooManyFunctions") // One scheduler: picking, holding back and probing share its state.
internal class NarrationPlacePool private constructor(
    override val voice: NarrationVoice,
    override val voiceKey: String,
    override val location: String,
    places: List<NarrationPlace>,
    private val phone: (suspend () -> NarrationSynthesizer)?,
    scope: CoroutineScope,
    private val now: () -> Long,
) : NarrationSynthesizer {
    private enum class State {
        /** Generating, or ready to. */
        READY,

        /** Answered `503`: not asked again before [Seat.until]. */
        HELD,

        /** Could not be used; a background probe brings it back. */
        DOWN,
    }

    private class Seat(
        val place: NarrationPlace,
    ) {
        var state = State.READY
        var until = 0L
        var inFlight = 0

        /** Since when the server has said it stopped generating for memory (0 while it has not). */
        var stoppedSince = 0L
        var stopReason: String? = null
        var problem: NarrationServerProblem? = null
        var probe: Job? = null
    }

    private sealed interface Pick {
        data class Take(
            val seat: Seat,
        ) : Pick

        data object Phone : Pick

        data class Wait(
            val ms: Long,
        ) : Pick

        data class Fail(
            val problem: NarrationServerProblem,
        ) : Pick
    }

    private val seats = places.map(::Seat)
    private val lock = Any()

    /** Bumps on every change a waiting sentence may care about (a free slot, a computer back). */
    private val changes = MutableStateFlow(0L)
    private val probes = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val waiting = MutableStateFlow<String?>(null)
    override val waitingFor: StateFlow<String?> = waiting

    private val phoneLock = Mutex()
    private var phoneVoice: NarrationSynthesizer? = null
    private var phoneInFlight = 0
    private var phoneActive = false

    override val speedPlace: String = seats.joinToString("+") { it.place.id }

    override val parallelism: Int
        get() = synchronized(lock) { seats.filter { it.state != State.DOWN }.sumOf { it.place.slots } }.coerceAtLeast(1)

    /** The computers generating now, "this phone" while it stands in, or every chosen computer while none can. */
    override val locationName: String
        get() =
            synchronized(lock) {
                val working = seats.filter { it.state != State.DOWN }
                when {
                    working.isNotEmpty() -> working.joinToString(" + ") { it.place.name }
                    phoneActive -> PHONE_NAME
                    else -> seats.joinToString(" + ") { it.place.name }
                }
            }

    override val inferenceThreads: Int? get() = phoneVoice?.inferenceThreads

    override suspend fun fitToDevice() {
        phoneVoice?.fitToDevice()
    }

    override suspend fun synthesize(
        text: String,
        output: File,
    ): TtsPcmClip? {
        while (true) {
            val seat = acquire() ?: return onPhone(text, output)
            try {
                return seat.place.synthesize(text).also { succeeded(seat) }
            } catch (busy: NarrationServerBusy) {
                held(seat, busy)
            } catch (problem: NarrationServerProblem) {
                Timber.w(problem, "%s set aside; its sentence goes elsewhere", seat.place.name)
                down(seat, problem)
            } finally {
                release(seat)
            }
        }
    }

    override fun close() {
        probes.cancel()
        seats.forEach { it.place.close() }
        val standIn = synchronized(lock) { phoneVoice.also { phoneVoice = null } }
        standIn?.close()
    }

    /** A free slot on a computer, or null when this phone should generate the sentence. */
    private suspend fun acquire(): Seat? {
        while (true) {
            val seen = changes.value
            when (val pick = synchronized(lock) { pick(now()) }) {
                is Pick.Take -> return pick.seat
                Pick.Phone -> return null
                is Pick.Fail -> throw pick.problem
                is Pick.Wait -> withTimeoutOrNull(pick.ms) { changes.first { it != seen } }
            }
        }
    }

    private fun pick(at: Long): Pick {
        seats.filter { it.state == State.HELD && it.until <= at }.forEach { it.state = State.READY }
        val ready = seats.filter { it.state == State.READY }
        ready.filter { it.inFlight < it.place.slots }.maxByOrNull { it.place.slots - it.inFlight }?.let { seat ->
            seat.inFlight++
            if (phoneActive) {
                phoneActive = false
                changed()
            }
            return Pick.Take(seat)
        }
        if (ready.isNotEmpty()) return Pick.Wait(FULL_WAIT_MS)
        seats.filter { it.state == State.HELD }.minOfOrNull { it.until }?.let { return Pick.Wait((it - at).coerceAtLeast(1L)) }
        // Every computer is out.
        if (phone != null) {
            if (!phoneActive) {
                phoneActive = true
                changed()
            }
            return Pick.Phone
        }
        return Pick.Fail(failure())
    }

    private fun failure(): NarrationServerProblem =
        NarrationServerPlanning.poolFailure(
            seats.map { it.place.name to (it.problem ?: NarrationServerProblem("${it.place.name} is out")) },
        )

    private fun succeeded(seat: Seat) =
        synchronized(lock) {
            if (seat.stoppedSince != 0L) {
                seat.stoppedSince = 0L
                seat.stopReason = null
                changed()
            }
        }

    /** The server holds back for memory: ask it again when it says, unless it has been stopped too long. */
    private fun held(
        seat: Seat,
        busy: NarrationServerBusy,
    ) = synchronized(lock) {
        if (seat.state == State.DOWN) return@synchronized
        val at = now()
        if (busy.stopped) {
            if (seat.stoppedSince == 0L) seat.stoppedSince = at
            seat.stopReason = busy.message
            if (at - seat.stoppedSince >= MAX_STOPPED_WAIT_MS) {
                markDown(seat, NarrationServerProblem("${busy.message}. Resume when it has memory again", cause = busy))
                return@synchronized
            }
        }
        seat.state = State.HELD
        seat.until = maxOf(seat.until, at + busy.retryAfterMs)
        changed()
    }

    private fun down(
        seat: Seat,
        problem: NarrationServerProblem,
    ) = synchronized(lock) { if (seat.state != State.DOWN) markDown(seat, problem) }

    private fun markDown(
        seat: Seat,
        problem: NarrationServerProblem,
    ) {
        seat.state = State.DOWN
        seat.problem = problem
        seat.stoppedSince = 0L
        seat.stopReason = null
        changed()
        if (seat.probe?.isActive != true) seat.probe = probes.launch { probe(seat) }
    }

    /** Checks a computer that is out, less and less often, until it can generate again. */
    private suspend fun probe(seat: Seat) {
        var wait = PROBE_FIRST_MS
        while (true) {
            delay(wait)
            val problem =
                try {
                    seat.place.connect(checkMemory = true)
                    null
                } catch (problem: NarrationServerProblem) {
                    problem
                }
            if (problem == null) {
                synchronized(lock) {
                    seat.state = State.READY
                    seat.problem = null
                    changed()
                }
                return
            }
            synchronized(lock) { seat.problem = problem }
            wait = (wait * 2).coerceAtMost(PROBE_MAX_MS)
        }
    }

    private fun release(seat: Seat) {
        synchronized(lock) {
            seat.inFlight--
            changed()
        }
        closePhoneIfIdle()
    }

    /** This phone generates while every computer is out, one sentence at a time. */
    private suspend fun onPhone(
        text: String,
        output: File,
    ): TtsPcmClip? {
        synchronized(lock) { phoneInFlight++ }
        try {
            return phoneLock.withLock {
                val standIn =
                    phoneVoice ?: try {
                        checkNotNull(phone)().also { opened -> synchronized(lock) { phoneVoice = opened } }
                    } catch (unavailable: NarrationVoiceUnavailable) {
                        // Without a voice here, the computers' problem is the one worth reporting.
                        Timber.w(unavailable, "This phone cannot stand in for the computers")
                        throw synchronized(lock) { failure() }
                    }
                standIn.synthesize(text, output)
            }
        } finally {
            synchronized(lock) { phoneInFlight-- }
            closePhoneIfIdle()
        }
    }

    /** Frees this phone's model once a computer is back and the phone has no sentence left. */
    private fun closePhoneIfIdle() {
        val idle =
            synchronized(lock) {
                if (phoneVoice != null && phoneInFlight == 0 && seats.any { it.state != State.DOWN }) {
                    phoneVoice.also { phoneVoice = null }
                } else {
                    null
                }
            }
        idle?.close()
    }

    /** Wakes waiting sentences and refreshes why generation is waiting. Call holding [lock]. */
    private fun changed() {
        changes.update { it + 1 }
        val generating = phoneActive || seats.any { it.state == State.READY && it.stopReason == null }
        waiting.value =
            if (generating) null else seats.firstNotNullOfOrNull { seat -> seat.stopReason?.takeIf { seat.state != State.DOWN } }
    }

    companion object {
        const val PHONE_NAME = "this phone"

        /** How long the computers may all stay stopped for memory before generation pauses (or the phone takes over). */
        const val MAX_STOPPED_WAIT_MS = 15 * 60_000L
        const val PROBE_FIRST_MS = 15_000L
        const val PROBE_MAX_MS = 5 * 60_000L

        /** Every computer is full: a release wakes the wait sooner. */
        private const val FULL_WAIT_MS = 60_000L

        /**
         * Connects to every place at once. Those that answer start generating; the rest are probed in
         * the background. Throws when none answers and there is no [phone] to fall back on.
         */
        @Suppress("LongParameterList") // The synthesizer's identity plus where it runs.
        suspend fun open(
            voice: NarrationVoice,
            voiceKey: String,
            location: String,
            places: List<NarrationPlace>,
            phone: (suspend () -> NarrationSynthesizer)?,
            scope: CoroutineScope,
            now: () -> Long = System::currentTimeMillis,
        ): NarrationPlacePool {
            val problems =
                coroutineScope {
                    places
                        .map { place ->
                            async {
                                try {
                                    place.connect(checkMemory = false)
                                    null
                                } catch (problem: NarrationServerProblem) {
                                    problem
                                }
                            }
                        }.awaitAll()
                }
            if (phone == null && problems.all { it != null }) {
                places.forEach(NarrationPlace::close)
                throw NarrationServerPlanning.poolFailure(
                    places.zip(problems.filterNotNull()).map { (place, problem) ->
                        place.name to
                            problem
                    },
                )
            }
            return NarrationPlacePool(voice, voiceKey, location, places, phone, scope, now).apply {
                synchronized(lock) {
                    seats.zip(problems).forEach { (seat, problem) -> if (problem != null) markDown(seat, problem) }
                }
            }
        }
    }
}
