package com.vinicius741.webnovelarchiver.perf

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import com.google.gson.Gson
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter

/**
 * Collects per-frame total durations via Window.OnFrameMetricsAvailableListener (API 24+;
 * minSdk 26) and appends one JSON line per frame to the session's frames.jsonl on a private
 * handler thread. Samples carry the screen tag that was current when the frame was presented;
 * the jank flag marks frames slower than twice the display frame interval.
 */
internal class PerfFrameCollector(
    private val outputFile: File,
    refreshRateHz: Double,
    private val tagProvider: () -> String,
    private val nowNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
) {
    private val gson = Gson()
    private val jankThresholdNanos = PerfFramePlanning.jankThresholdNanos(refreshRateHz)
    private val thread = HandlerThread("perf-frames").apply { start() }
    private val handler = Handler(thread.looper)
    private var writer: BufferedWriter? = null
    var sampleCount = 0L
        private set
    var droppedCount = 0L
        private set

    private val frameListener =
        Window.OnFrameMetricsAvailableListener { _, frameMetrics, _ ->
            appendSample(frameMetrics.getMetric(FrameMetrics.TOTAL_DURATION))
        }

    private val periodicFlush =
        object : Runnable {
            override fun run() {
                runCatching { writer?.flush() }
                if (thread.isAlive) handler.postDelayed(this, FLUSH_PERIOD_MILLIS)
            }
        }

    fun start(window: Window) {
        runCatching {
            writer = BufferedWriter(FileWriter(outputFile, false))
            window.addOnFrameMetricsAvailableListener(frameListener, handler)
            handler.postDelayed(periodicFlush, FLUSH_PERIOD_MILLIS)
        }
    }

    private fun appendSample(durationNanos: Long) {
        if (sampleCount >= PerfSessionContract.MAX_FRAME_SAMPLES) {
            droppedCount++
            return
        }
        val target = writer ?: return
        val sample =
            PerfFrameSampleExport(
                t = nowNanos(),
                durationMicros = durationNanos / 1_000,
                tag = tagProvider(),
                jank = durationNanos > jankThresholdNanos,
            )
        runCatching {
            target.write(gson.toJson(sample))
            target.write("\n")
        }.onFailure { droppedCount++ }
        sampleCount++
    }

    /** Flushes pending lines and stops collection; safe to call from the main thread. */
    fun close(window: Window?) {
        handler.removeCallbacksAndMessages(null)
        runCatching { window?.removeOnFrameMetricsAvailableListener(frameListener) }
        // A terminal flush posted to the collector thread; quitSafely runs it before the looper dies.
        handler.post {
            runCatching { writer?.flush() }
            runCatching { writer?.close() }
            writer = null
        }
        thread.quitSafely()
    }

    private companion object {
        const val FLUSH_PERIOD_MILLIS = 1000L
    }
}
