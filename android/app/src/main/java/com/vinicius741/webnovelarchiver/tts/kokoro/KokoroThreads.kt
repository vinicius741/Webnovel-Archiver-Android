package com.vinicius741.webnovelarchiver.tts.kokoro

import java.io.File

/**
 * How many threads Kokoro computes on: one per fast core. The engine splits each sentence evenly
 * across its threads, so a thread on a slow efficiency core holds the others back, and fast cores
 * left idle are speed thrown away. The speed test on a Snapdragon 8 Elite (all eight cores fast)
 * measured 1.72x real time at 4 threads and 2.51x at 8.
 */
internal object KokoroThreads {
    /** When the core clocks cannot be read: the fixed count used before cores were counted. */
    const val FALLBACK = 4
    private const val MIN = 2
    private const val MAX = 8

    /**
     * A core within this share of the fastest core's top clock counts as fast. Efficiency cores
     * sit lower: about 0.69 on a Snapdragon 8 Gen 3 and 0.58 on a Tensor G3, while the slower
     * cluster of a Snapdragon 8 Elite reaches 0.79.
     */
    private const val FAST_CORE_SHARE = 0.7

    /** Read once: core clocks do not change while the app runs. */
    val forThisPhone: Int by lazy { forClocks(KokoroCpu.coreMaxKhz()) }

    /**
     * [forThisPhone], capped at the cores Android lets this thread run on right now. A background
     * app can be confined to fewer cores than the phone has; null (unreadable) keeps the phone's count.
     */
    fun forNow(): Int = forAllowed(forThisPhone, KokoroCpu.allowedCoreCount())

    fun forAllowed(
        phone: Int,
        allowed: Int?,
    ): Int = if (allowed == null || allowed <= 0) phone else minOf(phone, allowed.coerceAtLeast(MIN))

    /** [maxKhz] holds each core's top clock, null where it could not be read. */
    fun forClocks(maxKhz: List<Long?>): Int {
        val known = maxKhz.filterNotNull().filter { it >= KokoroCpu.KHZ_PER_MHZ }
        if (known.isEmpty() || known.size != maxKhz.size) return FALLBACK
        val fastest = known.max()
        return known.count { it >= fastest * FAST_CORE_SHARE }.coerceIn(MIN, MAX)
    }
}

/** What Linux reports about this phone's cores, shared by the thread count and the speed test. */
internal object KokoroCpu {
    const val DIR = "/sys/devices/system/cpu"
    const val KHZ_PER_MHZ = 1_000L
    private val CORE_NAME = Regex("cpu\\d+")

    /** Counts cores that are switched off too, which the runtime's count leaves out. */
    fun coreCount(): Int {
        val listed = File(DIR).listFiles { file -> CORE_NAME.matches(file.name) }?.size ?: 0
        return maxOf(listed, Runtime.getRuntime().availableProcessors())
    }

    /** Each core's top clock; null where unreadable. Emulators report placeholders below 1 MHz. */
    fun coreMaxKhz(cores: Int = coreCount()): List<Long?> = (0 until cores).map { readLong("$DIR/cpu$it/cpufreq/cpuinfo_max_freq") }

    fun coreCurrentKhz(cores: Int): List<Long?> = (0 until cores).map { readLong("$DIR/cpu$it/cpufreq/scaling_cur_freq") }

    /** Cores the calling thread may run on now (its cpuset), or null when unreadable. */
    fun allowedCoreCount(): Int? =
        runCatching {
            File("/proc/thread-self/status")
                .readLines()
                .firstOrNull { it.startsWith("Cpus_allowed_list:") }
                ?.substringAfter(':')
                ?.let(::countCpuList)
        }.getOrNull()

    /** Counts the cores in a Linux CPU list such as `0-3,6,7`; null when it does not parse. */
    fun countCpuList(list: String): Int? {
        var count = 0
        for (part in list.trim().split(',')) {
            if (part.isBlank()) continue
            val bounds = part.trim().split('-')
            val first = bounds[0].toIntOrNull() ?: return null
            val last = bounds.getOrNull(1)?.toIntOrNull() ?: if (bounds.size == 1) first else return null
            if (last < first) return null
            count += last - first + 1
        }
        return count.takeIf { it > 0 }
    }

    private fun readLong(path: String): Long? = runCatching { File(path).readText().trim().toLong() }.getOrNull()
}
