package com.vinicius741.webnovelarchiver.perf

import java.util.concurrent.atomic.AtomicLong

/** Gson-exported session document (PerfSessionContract.SESSION_FILE). Field names are the
 *  machine-readable contract consumed by scripts/perf — rename only with a version bump. */
data class PerfSessionExport(
    val format: String,
    val version: Int,
    val session: PerfSessionMetaExport,
    val device: PerfDeviceMetaExport,
    val events: List<PerfEventExport>,
    val memory: List<PerfMemoryExport>,
    val overhead: PerfOverheadExport,
)

data class PerfSessionMetaExport(
    val runId: String,
    val scenario: String,
    val iteration: String,
    val completeOn: String,
    val completeOnRoute: String?,
    val startedAtWallClockMillis: Long,
    val processStartElapsedNanos: Long,
    val scenarioCompleteElapsedNanos: Long?,
    val deadlineFired: Boolean,
    val ended: Boolean,
)

data class PerfDeviceMetaExport(
    val appId: String,
    val appVersion: String,
    val sdkInt: Int,
    val model: String,
    val densityDpi: Int,
    val refreshRateHz: Double,
)

data class PerfEventExport(
    val name: String,
    val t: Long,
    val durationMillis: Long?,
    val detail: Map<String, String>?,
)

data class PerfMemoryExport(
    val t: Long,
    val label: String,
    val totalPssKb: Int,
    val dalvikPssKb: Int,
    val nativePssKb: Int,
    val otherPssKb: Int,
    val javaHeapUsedKb: Long,
    val javaHeapMaxKb: Long,
)

data class PerfOverheadExport(
    val eventRecordCalls: Long,
    val eventRecordMeanNanos: Long,
    val eventRecordMaxNanos: Long,
    val frameSamples: Long,
    val droppedEvents: Int,
    val droppedMemorySamples: Int,
    val droppedFrameSamples: Long,
    val flushCount: Long,
)

/** One appended line of frames.jsonl: frame total duration (see docs for what TOTAL_DURATION
 *  measures) tagged with the screen that was current when the frame was presented. */
data class PerfFrameSampleExport(
    val t: Long,
    val durationMicros: Long,
    val tag: String,
    val jank: Boolean,
)

/** Thread-safe call-cost accumulator for recorder overhead self-measurement. */
class PerfOverheadAccumulator {
    private val calls = AtomicLong()
    private val totalNanos = AtomicLong()
    private val maxNanos = AtomicLong()

    fun record(
        startNanos: Long,
        endNanos: Long,
    ) {
        val cost = (endNanos - startNanos).coerceAtLeast(0)
        calls.incrementAndGet()
        totalNanos.addAndGet(cost)
        maxNanos.accumulateAndGet(cost) { current, candidate -> if (candidate > current) candidate else current }
    }

    fun snapshot(): PerfOverheadExport =
        PerfOverheadExport(
            eventRecordCalls = calls.get(),
            eventRecordMeanNanos = totalNanos.get() / calls.get().coerceAtLeast(1),
            eventRecordMaxNanos = maxNanos.get(),
            frameSamples = 0,
            droppedEvents = 0,
            droppedMemorySamples = 0,
            droppedFrameSamples = 0,
            flushCount = 0,
        )
}

/** Pure jank classification shared by the frame collector and its unit tests. */
object PerfFramePlanning {
    const val JANK_MULTIPLIER = 2.0

    fun jankThresholdNanos(refreshRateHz: Double): Long = (1_000_000_000.0 / refreshRateHz.coerceAtLeast(1.0) * JANK_MULTIPLIER).toLong()

    fun isJank(
        durationNanos: Long,
        refreshRateHz: Double,
    ): Boolean = durationNanos > jankThresholdNanos(refreshRateHz)
}
