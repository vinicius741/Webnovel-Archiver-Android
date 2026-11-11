package com.vinicius741.webnovelarchiver.tts.kokoro

import java.util.Locale

/** The phone the speed test ran on, for reading its results later. */
internal data class KokoroBenchmarkDevice(
    val manufacturer: String,
    val model: String,
    /** The chip, when Android reports it (Android 12+). */
    val soc: String?,
    val androidRelease: String,
    val sdk: Int,
    val cores: Int,
    /** Each core's top clock, which shows how many fast cores the chip has; empty if unreadable. */
    val coreMaxMhz: List<Int>,
    val totalRamMb: Long,
)

/** The phone's state when a check ran: what the test could still have used. */
internal data class KokoroHeadroom(
    val availableRamMb: Long,
    /** Android's thermal status name, e.g. "none" or "moderate". */
    val thermalStatus: String,
    /** Android's thermal headroom: 1.0 is where throttling begins; null when the phone does not say. */
    val thermalHeadroom: Float?,
)

/** Everything about a speed test besides its timings. */
internal data class KokoroBenchmarkSetup(
    val appVersion: String,
    val startedAt: String,
    val device: KokoroBenchmarkDevice,
    val voiceName: String,
    val listeningRate: Float,
    val appThreads: Int,
    /** Whether narration already held its own copy of the model, which competes for memory. */
    val narrationModelLoaded: Boolean,
    val atStart: KokoroHeadroom,
)

internal data class KokoroSentenceTiming(
    val chars: Int,
    val audioMs: Long,
    val generateMs: Long,
) {
    /** Seconds of audio made per second of work: below the listening speed, playback waits. */
    val speed: Double get() = KokoroBenchmarkPlanning.speed(audioMs, generateMs)
}

/** How hard the phone worked while a run generated. */
internal data class KokoroCpuUsage(
    /** CPU time the whole app used while generating, across all cores. */
    val cpuMs: Long,
    val wallMs: Long,
    /** Percent of the generating threads' work done on each core, by core number. */
    val workPercentByCore: List<Int>,
    /** Each core's average clock as a percent of its top clock; empty if unreadable. */
    val clockPercentByCore: List<Int>,
) {
    /** On average, how many cores were fully busy. */
    val coresBusy: Double get() = if (wallMs <= 0L) 0.0 else cpuMs.toDouble() / wallMs
}

/** [engines] copies of the model, each loaded with [threads], generating the test sentences at once. */
internal data class KokoroBenchmarkRun(
    val engines: Int,
    val threads: Int,
    val loadMs: Long = 0L,
    /** The first generation after a load is slower; it is timed but left out of [speed]. */
    val warmupMs: Long = 0L,
    /** What one copy of the model added to the app's memory. */
    val modelMemoryMb: Long? = null,
    val wallMs: Long = 0L,
    val sentences: List<KokoroSentenceTiming> = emptyList(),
    val cpu: KokoroCpuUsage? = null,
    /** Why the run did not happen or did not finish. */
    val error: String? = null,
) {
    val label: String get() = if (engines == 1) "$threads threads" else "$engines copies × $threads threads"
    val audioMs: Long get() = sentences.sumOf { it.audioMs }

    /** Audio made per second of real time, all copies together. */
    val speed: Double get() = KokoroBenchmarkPlanning.speed(audioMs, wallMs)
    val slowestSpeed: Double get() = sentences.minOfOrNull { it.speed } ?: 0.0
    val completed: Boolean get() = error == null && sentences.isNotEmpty()
}

internal data class KokoroBenchmarkResult(
    val setup: KokoroBenchmarkSetup,
    val runs: List<KokoroBenchmarkRun>,
    val atEnd: KokoroHeadroom,
    val totalMs: Long,
    val cancelled: Boolean = false,
)

/** A step of the speed test: [engines] copies of the model at [threads] each. */
internal data class KokoroBenchmarkStep(
    val engines: Int,
    val threads: Int,
)

/** One reading of a thread from `/proc/self/task/<id>/stat`. */
internal data class KokoroTaskSample(
    val cpuTicks: Long,
    val core: Int,
)

/** What the Kokoro speed test generates and how its results read; pure, so it is unit tested. */
internal object KokoroBenchmarkPlanning {
    /** Spoken once after each load so the timed sentences measure a warmed-up engine. */
    const val WARMUP_SENTENCE = "The bell rang once, and the hall fell silent."

    /**
     * Novel-like prose of the lengths narration meets: a dialogue line, plain narration, two long
     * sentences with clauses, and a short question. About half a minute of audio.
     */
    val SENTENCES: List<String> =
        listOf(
            "\"You're late,\" Mira said, without looking up from the map.",
            "He set the lantern on the table and waited for the others to find their seats.",
            "Beyond the walls, the river had swollen with three days of rain, and the old bridge groaned under " +
                "every cart that crossed it, though no one in the village seemed to notice.",
            "Was it really worth it?",
            "By the time the fourth watch began, Captain Arlen had read the letter twice, folded it into his coat, " +
                "and decided that the city would hear nothing of it until morning.",
        )

    /** A second copy of the model is loaded only with this much RAM free after the first. */
    const val PARALLEL_MIN_FREE_MB = 1_500L

    /** Below this margin over the listening speed, a slow sentence can still open a gap. */
    private const val COMFORT_MARGIN = 1.15

    /** A drop this large between the first and repeated run means the phone slowed down from heat. */
    private const val THROTTLE_DROP = 0.10
    private val CANDIDATE_THREADS = listOf(2, 4, 6, 8)
    private const val MAX_PARALLEL_THREADS = 4

    /**
     * The steps to run, in order: the app's own setting first, while the phone is coolest, then the
     * other thread counts, then two copies generating at once (does generating sentences in parallel
     * beat one at a time?), then the app's setting again, so heat throttling shows as a drop.
     */
    fun plan(
        cores: Int,
        appThreads: Int,
    ): List<KokoroBenchmarkStep> {
        val usable = cores.coerceAtLeast(1)
        val app = KokoroBenchmarkStep(1, appThreads)
        val others = CANDIDATE_THREADS.filter { it <= usable && it != appThreads }.map { KokoroBenchmarkStep(1, it) }
        val parallel = (usable / 2).coerceAtMost(MAX_PARALLEL_THREADS).takeIf { it >= 1 && usable >= 4 }?.let { KokoroBenchmarkStep(2, it) }
        val between = others + listOfNotNull(parallel)
        return if (between.isEmpty()) listOf(app) else listOf(app) + between + app
    }

    fun speed(
        audioMs: Long,
        generateMs: Long,
    ): Double = if (generateMs <= 0L) 0.0 else audioMs.toDouble() / generateMs

    /**
     * Reads a `/proc/<pid>/task/<tid>/stat` line: the thread's CPU time so far and the core it last
     * ran on. The name field may hold spaces and parentheses, so fields count from its closing one.
     */
    fun parseTaskStat(line: String): KokoroTaskSample? {
        val fields =
            line
                .substringAfterLast(')', "")
                .trim()
                .split(' ')
                .filter { it.isNotEmpty() }
        // After the name: state is field 3, utime 14, stime 15, processor 39.
        if (fields.size <= PROCESSOR_INDEX) return null
        val user = fields[UTIME_INDEX].toLongOrNull() ?: return null
        val system = fields[STIME_INDEX].toLongOrNull() ?: return null
        val core = fields[PROCESSOR_INDEX].toIntOrNull() ?: return null
        return KokoroTaskSample(user + system, core)
    }

    private const val UTIME_INDEX = 11
    private const val STIME_INDEX = 12
    private const val PROCESSOR_INDEX = 36

    /** Shares of [ticksByCore] in whole percents, one per core of [cores]. */
    fun percents(
        ticksByCore: Map<Int, Long>,
        cores: Int,
    ): List<Int> {
        val total = ticksByCore.values.sum()
        return List(cores) { core -> if (total <= 0L) 0 else Math.round((ticksByCore[core] ?: 0L) * 100.0 / total).toInt() }
    }

    /** Groups cores that share a top clock: big.LITTLE chips show as two to four clusters. */
    fun clusters(coreMaxMhz: List<Int>): List<Pair<List<Int>, Int>> =
        coreMaxMhz.indices
            .groupBy { coreMaxMhz[it] }
            .map { (mhz, cores) -> cores to mhz }
            .sortedBy { it.second }

    /** The plain-language conclusion for the app's own setting at [rate]. */
    fun verdict(
        run: KokoroBenchmarkRun?,
        rate: Float,
    ): String {
        if (run == null || !run.completed) return "No result: the test did not finish a run with the app's setting."
        val needed = rate.toDouble()
        val made = "With ${run.threads} threads (the app's setting), Kokoro makes audio ${times(run.speed)} as fast as it plays."
        return when {
            run.speed < needed ->
                "$made At your ${times(needed)} listening speed it needs ${times(needed)}, so playback " +
                    "pauses between sentences while it catches up."
            run.slowestSpeed < needed || run.speed < needed * COMFORT_MARGIN ->
                "$made That barely covers your ${times(needed)} listening speed, so expect occasional pauses."
            else -> "$made That is ahead of your ${times(needed)} listening speed, so generation keeps up."
        }
    }

    fun fastest(runs: List<KokoroBenchmarkRun>): KokoroBenchmarkRun? = runs.filter { it.completed }.maxByOrNull { it.speed }

    /** Compares the app's setting at the start with its repeat at the end of the test. */
    fun throttleNote(
        runs: List<KokoroBenchmarkRun>,
        appThreads: Int,
    ): String? {
        val repeats = runs.filter { it.engines == 1 && it.threads == appThreads && it.completed }
        if (repeats.size < 2) return null
        val first = repeats.first().speed
        val last = repeats.last().speed
        if (first <= 0.0) return null
        val drop = (first - last) / first
        val change = "The app's setting ran at ${times(first)} first and ${times(last)} at the end"
        return if (drop >= THROTTLE_DROP) "$change: the phone slowed down as it warmed up." else "$change: no sign of heat throttling."
    }

    fun times(value: Double): String = String.format(Locale.US, "%.2fx", value)

    fun seconds(ms: Long): String = String.format(Locale.US, "%.1f s", ms / 1000.0)
}
