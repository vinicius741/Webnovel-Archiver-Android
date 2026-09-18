package com.vinicius741.webnovelarchiver.perf

import android.content.Context
import android.os.SystemClock
import android.view.Window
import com.google.gson.Gson
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A live performance session: owns the pure [PerfSessionState], an off-main executor for
 * memory snapshots and session.json rewrites, and the frame collector. The host runner polls
 * session.json (via run-as) — flushes are debounced except completion/end, which flush
 * immediately. All public methods are safe from the main thread.
 */
internal class PerfSessionRunner(
    val state: PerfSessionState,
    private val appContext: Context,
    private val appVersion: String,
    private val refreshRateHz: Double,
    private val nowNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
) {
    @Volatile var screenTag = "startup"

    private val gson = Gson()
    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "perf-session").apply { isDaemon = true } }
    private val scheduler: java.util.concurrent.ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable, "perf-session-timer").apply { isDaemon = true } }
    private val framesFile = File(File(appContext.cacheDir, PerfSessionContract.SESSION_DIR), PerfSessionContract.FRAMES_FILE)
    private val sessionFile = File(File(appContext.cacheDir, PerfSessionContract.SESSION_DIR), PerfSessionContract.SESSION_FILE)
    private val frameCollector =
        PerfFrameCollector(framesFile, refreshRateHz, tagProvider = { screenTag }, nowNanos = nowNanos)
    private val flushScheduled =
        java.util.concurrent.atomic
            .AtomicBoolean(false)
    private var flushCount = 0L
    private val overhead = PerfOverheadAccumulator()

    /** Starts collection and arms the scenario deadline ([maxDurationMillis] from the launch intent). */
    fun start(
        window: Window,
        maxDurationMillis: Long,
    ) {
        framesFile.parentFile?.mkdirs()
        offerEvent(PerfSessionContract.EVENT_SESSION_START)
        frameCollector.start(window)
        submitMemorySnapshot("session_start")
        scheduler.schedule(
            {
                if (state.shouldFireDeadline(nowNanos())) {
                    state.offerEvent(PerfSessionContract.EVENT_DEADLINE, nowNanos())
                    forceFlush()
                }
            },
            maxDurationMillis,
            TimeUnit.MILLISECONDS,
        )
    }

    /** Returns true when this event completed the scenario (first complete-on match). */
    fun offerEvent(
        name: String,
        durationMillis: Long? = null,
        detail: Map<String, String> = emptyMap(),
    ): Boolean {
        val t = nowNanos()
        val completes = state.offerEvent(name, t, durationMillis, detail)
        if (completes) {
            state.offerEvent(PerfSessionContract.EVENT_SCENARIO_COMPLETE, nowNanos())
            forceFlush()
        } else {
            scheduleFlush()
        }
        return completes
    }

    fun submitMemorySnapshot(label: String) {
        executor.submit {
            state.offerMemory(PerfMemorySampler.readSnapshot(label, nowNanos))
            scheduleFlush()
        }
    }

    fun recordCallCost(startNanos: Long) {
        overhead.record(startNanos, nowNanos())
    }

    fun finish(reason: String) {
        state.offerEvent(PerfSessionContract.EVENT_SESSION_END, nowNanos(), detail = mapOf("reason" to reason))
        state.markEnded()
        forceFlush()
        scheduler.shutdownNow()
        executor.shutdown()
        runCatching { executor.awaitTermination(2, TimeUnit.SECONDS) }
    }

    fun close(window: Window?) {
        frameCollector.close(window)
    }

    /**
     * Trailing debounce: exactly one delayed flush is armed at a time; the armed write always
     * happens, and events arriving during the wait or the write itself re-arm a follow-up. A
     * plain "skip if within N ms of the last flush" would silently swallow the final write when
     * an event lands just after a flush and no further events follow.
     */
    private fun scheduleFlush() {
        if (!flushScheduled.compareAndSet(false, true)) return
        scheduler.schedule(
            {
                flushScheduled.set(false)
                executor.submit(::writeSessionFile)
            },
            PerfSessionContract.FLUSH_DEBOUNCE_MILLIS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun forceFlush() {
        executor.submit(::writeSessionFile)
    }

    private fun writeSessionFile() {
        runCatching {
            flushCount++
            sessionFile.parentFile?.mkdirs()
            val tmp = File(sessionFile.parentFile, sessionFile.name + ".tmp")
            tmp.writeText(gson.toJson(buildExport()))
            Files.move(tmp.toPath(), sessionFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun buildExport(): PerfSessionExport =
        PerfSessionExport(
            format = PerfSessionContract.EXPORT_FORMAT,
            version = PerfSessionContract.EXPORT_VERSION,
            session =
                PerfSessionMetaExport(
                    runId = state.runId,
                    scenario = state.scenario,
                    iteration = state.iteration,
                    completeOn = state.completeOn.event,
                    completeOnRoute = state.completeOn.route,
                    startedAtWallClockMillis = state.startedAtWallClockMillis,
                    processStartElapsedNanos = state.processStartNanos,
                    scenarioCompleteElapsedNanos = state.scenarioCompleteNanos,
                    deadlineFired = state.deadlineFired,
                    ended = state.ended,
                ),
            device = PerfEnvironment.deviceMeta(appContext, appVersion, refreshRateHz),
            events =
                state.snapshotEvents().map {
                    PerfEventExport(name = it.name, t = it.tNanos, durationMillis = it.durationMillis, detail = it.detail.ifEmpty { null })
                },
            memory =
                state.snapshotMemory().map {
                    PerfMemoryExport(
                        t = it.tNanos,
                        label = it.label,
                        totalPssKb = it.totalPssKb,
                        dalvikPssKb = it.dalvikPssKb,
                        nativePssKb = it.nativePssKb,
                        otherPssKb = it.otherPssKb,
                        javaHeapUsedKb = it.javaHeapUsedKb,
                        javaHeapMaxKb = it.javaHeapMaxKb,
                    )
                },
            overhead =
                overhead.snapshot().copy(
                    frameSamples = frameCollector.sampleCount,
                    droppedEvents = state.droppedEvents,
                    droppedMemorySamples = state.droppedMemorySamples,
                    droppedFrameSamples = frameCollector.droppedCount,
                    flushCount = flushCount,
                ),
        )
}
