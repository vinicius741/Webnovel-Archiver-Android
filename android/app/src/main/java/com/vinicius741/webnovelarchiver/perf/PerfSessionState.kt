package com.vinicius741.webnovelarchiver.perf

/**
 * Pure session state machine: bounded event/memory buffers, complete-on matching, and
 * deadline evaluation. All timestamps are caller-supplied monotonic nanoseconds so this class is
 * unit-testable on the JVM; the Android-facing recorder owns the clocks and files.
 */
class PerfSessionState(
    val runId: String,
    val scenario: String,
    val iteration: String,
    val completeOn: PerfCompleteOnRule,
    val deadlineNanos: Long,
    val processStartNanos: Long,
    val startedAtWallClockMillis: Long,
) {
    private val events = ArrayDeque<PerfRecordedEvent>()
    private val memory = ArrayDeque<PerfRecordedMemory>()

    var droppedEvents = 0
        private set
    var droppedMemorySamples = 0
        private set
    var scenarioCompleteNanos: Long? = null
        private set
    var deadlineFired = false
        private set
    var ended = false
        private set

    /** Offers an event; returns true when this event completed the scenario (first match only). */
    @Synchronized
    fun offerEvent(
        name: String,
        tNanos: Long,
        durationMillis: Long? = null,
        detail: Map<String, String> = emptyMap(),
    ): Boolean {
        if (events.size >= PerfSessionContract.MAX_EVENTS) {
            droppedEvents++
            return false
        }
        val sanitized = detail.mapValues { (_, value) -> value.take(PerfSessionContract.MAX_DETAIL_LENGTH) }
        events.addLast(PerfRecordedEvent(name, tNanos, durationMillis, sanitized))
        val completesNow = scenarioCompleteNanos == null && completeOn.matches(name, sanitized[PerfSessionContract.DETAIL_ROUTE])
        if (completesNow) scenarioCompleteNanos = tNanos
        return completesNow
    }

    @Synchronized
    fun offerMemory(sample: PerfRecordedMemory) {
        if (memory.size >= PerfSessionContract.MAX_MEMORY_SAMPLES) {
            droppedMemorySamples++
        } else {
            memory.addLast(sample)
        }
    }

    /** True exactly once when [nowNanos] passes the deadline without prior completion. */
    @Synchronized
    fun shouldFireDeadline(nowNanos: Long): Boolean {
        if (deadlineFired || ended) return false
        if (scenarioCompleteNanos != null || nowNanos < deadlineNanos) return false
        deadlineFired = true
        return true
    }

    @Synchronized
    fun markEnded() {
        ended = true
    }

    @Synchronized
    fun snapshotEvents(): List<PerfRecordedEvent> = events.toList()

    @Synchronized
    fun snapshotMemory(): List<PerfRecordedMemory> = memory.toList()
}

data class PerfRecordedEvent(
    val name: String,
    val tNanos: Long,
    val durationMillis: Long?,
    val detail: Map<String, String>,
)

data class PerfRecordedMemory(
    val tNanos: Long,
    val label: String,
    val totalPssKb: Int,
    val dalvikPssKb: Int,
    val nativePssKb: Int,
    val otherPssKb: Int,
    val javaHeapUsedKb: Long,
    val javaHeapMaxKb: Long,
)
