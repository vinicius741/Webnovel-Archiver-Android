package com.vinicius741.webnovelarchiver.app

import kotlinx.coroutines.delay
import kotlin.math.ceil

/** Keeps scheduler deadlines and their waits on the same clock. Billing timestamps remain real. */
interface AppTime {
    fun nowMillis(): Long

    fun elapsedMillis(): Long

    fun realDelayMillis(logicalMillis: Long): Long = logicalMillis.coerceAtLeast(1L)

    suspend fun sleep(logicalMillis: Long) {
        if (logicalMillis > 0) delay(realDelayMillis(logicalMillis))
    }

    data object Real : AppTime {
        override fun nowMillis(): Long = System.currentTimeMillis()

        override fun elapsedMillis(): Long = System.nanoTime() / 1_000_000L
    }
}

/** Uses a monotonic clock within a process and a stable epoch across simulated restarts. */
class ScaledAppTime(
    private val epochMillis: Long,
    private val speed: Double,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val monotonicNanos: () -> Long = System::nanoTime,
) : AppTime {
    private val startWall = wallClock()
    private val startNanos = monotonicNanos()

    init {
        require(speed.isFinite() && speed in 1.0..1000.0)
    }

    override fun nowMillis(): Long = epochMillis + ((startWall - epochMillis) * speed).toLong() + elapsedMillis()

    override fun elapsedMillis(): Long = ((monotonicNanos() - startNanos) / 1_000_000.0 * speed).toLong()

    override fun realDelayMillis(logicalMillis: Long): Long = ceil(logicalMillis / speed).toLong().coerceAtLeast(1L)
}
