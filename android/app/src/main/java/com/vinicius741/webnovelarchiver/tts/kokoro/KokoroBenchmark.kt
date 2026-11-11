package com.vinicius741.webnovelarchiver.tts.kokoro

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.OfflineTts
import com.vinicius741.webnovelarchiver.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Times Kokoro on this phone at several thread counts, and with two copies generating at once, and
 * records how much of the CPU, memory and thermal budget it used. It loads its own copies of the
 * model, one step at a time, so it measures the engine without narration's queue in the way.
 */
internal class KokoroBenchmark(
    private val context: Context,
    private val files: KokoroModelFiles = KokoroModelFiles(context),
) {
    /**
     * Runs every step of [KokoroBenchmarkPlanning.plan]. [shouldStop] ends the test early with what
     * it has so far; [onProgress] is called on a background thread.
     */
    suspend fun run(
        speakerId: Int,
        voiceName: String,
        listeningRate: Float,
        narrationModelLoaded: Boolean,
        shouldStop: () -> Boolean,
        onProgress: (String) -> Unit,
    ): KokoroBenchmarkResult =
        withContext(Dispatchers.IO) {
            val startedAt = SystemClock.elapsedRealtime()
            val device = device()
            val setup =
                KokoroBenchmarkSetup(
                    appVersion = BuildConfig.VERSION_NAME,
                    startedAt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()),
                    device = device,
                    voiceName = voiceName,
                    listeningRate = listeningRate,
                    appThreads = KokoroRuntime.NUM_THREADS,
                    narrationModelLoaded = narrationModelLoaded,
                    atStart = headroom(),
                )
            val steps = KokoroBenchmarkPlanning.plan(device.cores, KokoroRuntime.NUM_THREADS)
            val runs = mutableListOf<KokoroBenchmarkRun>()
            for ((index, step) in steps.withIndex()) {
                if (shouldStop()) break
                val label = KokoroBenchmarkRun(step.engines, step.threads).label
                runs +=
                    runStep(step, speakerId, shouldStop) { detail -> onProgress("Step ${index + 1} of ${steps.size} · $label\n$detail") }
            }
            KokoroBenchmarkResult(
                setup = setup,
                runs = runs,
                atEnd = headroom(),
                totalMs = SystemClock.elapsedRealtime() - startedAt,
                cancelled = shouldStop(),
            )
        }

    // A failing engine call must become a failed step, so the rest of the test still runs.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun runStep(
        step: KokoroBenchmarkStep,
        speakerId: Int,
        shouldStop: () -> Boolean,
        onProgress: (String) -> Unit,
    ): KokoroBenchmarkRun {
        val failed = KokoroBenchmarkRun(step.engines, step.threads)
        val engines = ArrayList<OfflineTts>()
        try {
            onProgress("Loading the model")
            val memoryBefore = Debug.getPss()
            val loadStart = SystemClock.elapsedRealtime()
            repeat(step.engines) {
                currentCoroutineContext().ensureActive()
                val free = availableRamMb()
                if (it > 0 && free < KokoroBenchmarkPlanning.PARALLEL_MIN_FREE_MB) {
                    return failed.copy(error = "skipped: only $free MB free for another copy")
                }
                engines += loadKokoroTts(files, step.threads) ?: return failed.copy(error = "the model failed to load")
            }
            val loadMs = SystemClock.elapsedRealtime() - loadStart
            val modelMemoryMb = ((Debug.getPss() - memoryBefore) / KB_PER_MB / step.engines).takeIf { it > 0L }
            onProgress("Warming up")
            val warmupStart = SystemClock.elapsedRealtime()
            onEach(engines) { it.generate(KokoroBenchmarkPlanning.WARMUP_SENTENCE, speakerId, 1.0f) }
            val warmup = SystemClock.elapsedRealtime() - warmupStart
            val measured = measure(engines, speakerId, shouldStop, onProgress)
            val stopped = measured.sentences.size < KokoroBenchmarkPlanning.SENTENCES.size * step.engines
            return failed.copy(
                loadMs = loadMs,
                warmupMs = warmup,
                modelMemoryMb = modelMemoryMb,
                wallMs = measured.wallMs,
                sentences = measured.sentences,
                cpu = measured.cpu,
                error = if (stopped) "stopped early" else null,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Timber.w(error, "Kokoro speed test step failed")
            return failed.copy(error = "failed: ${error.javaClass.simpleName}")
        } finally {
            engines.forEach { it.release() }
        }
    }

    private class Measured(
        val wallMs: Long,
        val sentences: List<KokoroSentenceTiming>,
        val cpu: KokoroCpuUsage,
    )

    /** Every engine speaks the test sentences at once while the CPU is sampled. */
    private suspend fun measure(
        engines: List<OfflineTts>,
        speakerId: Int,
        shouldStop: () -> Boolean,
        onProgress: (String) -> Unit,
    ): Measured =
        coroutineScope {
            val sampler = CpuSampler(coreCount())
            val cpuStart = Process.getElapsedCpuTime()
            val wallStart = SystemClock.elapsedRealtime()
            val sampling =
                launch(Dispatchers.Default) {
                    while (isActive) {
                        sampler.sample()
                        delay(SAMPLE_EVERY_MS)
                    }
                }
            val total = KokoroBenchmarkPlanning.SENTENCES.size
            // A failure here cancels the sampler with the rest of this scope.
            val sentences =
                onEach(engines) { engine ->
                    val timings = mutableListOf<KokoroSentenceTiming>()
                    for ((index, text) in KokoroBenchmarkPlanning.SENTENCES.withIndex()) {
                        if (shouldStop()) break
                        if (engine === engines.first()) onProgress("Sentence ${index + 1} of $total")
                        timings += timeSentence(engine, text, speakerId)
                    }
                    timings
                }.flatten()
            sampling.cancelAndJoin()
            sampler.sample()
            val wallMs = SystemClock.elapsedRealtime() - wallStart
            Measured(wallMs, sentences, sampler.usage(Process.getElapsedCpuTime() - cpuStart, wallMs))
        }

    /** Runs [block] on every engine at the same time, each on its own thread. */
    private suspend fun <T> onEach(
        engines: List<OfflineTts>,
        block: suspend (OfflineTts) -> T,
    ): List<T> = coroutineScope { engines.map { engine -> async(Dispatchers.IO) { block(engine) } }.awaitAll() }

    private fun timeSentence(
        engine: OfflineTts,
        text: String,
        speakerId: Int,
    ): KokoroSentenceTiming {
        val start = SystemClock.elapsedRealtime()
        val audio = engine.generate(text, speakerId, 1.0f)
        val generateMs = SystemClock.elapsedRealtime() - start
        val audioMs = if (audio.sampleRate > 0) audio.samples.size * MS_PER_SECOND / audio.sampleRate else 0L
        return KokoroSentenceTiming(text.length, audioMs, generateMs)
    }

    private fun device(): KokoroBenchmarkDevice {
        val cores = coreCount()
        val maxKhz = (0 until cores).map { readLong("$CPU_DIR/cpu$it/cpufreq/cpuinfo_max_freq") }
        return KokoroBenchmarkDevice(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE,
            androidRelease = Build.VERSION.RELEASE,
            sdk = Build.VERSION.SDK_INT,
            cores = cores,
            coreMaxMhz = if (maxKhz.all { it != null && it >= KHZ_PER_MHZ }) maxKhz.map { (it!! / KHZ_PER_MHZ).toInt() } else emptyList(),
            totalRamMb = memoryInfo().totalMem / BYTES_PER_MB,
        )
    }

    private fun headroom(): KokoroHeadroom {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val status =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                THERMAL_NAMES.getOrElse(power.currentThermalStatus) { "status ${power.currentThermalStatus}" }
            } else {
                "not reported"
            }
        val headroom =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) power.getThermalHeadroom(0).takeUnless { it.isNaN() } else null
        return KokoroHeadroom(availableRamMb(), status, headroom)
    }

    private fun availableRamMb(): Long = memoryInfo().availMem / BYTES_PER_MB

    private fun memoryInfo(): ActivityManager.MemoryInfo =
        ActivityManager.MemoryInfo().also { (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it) }

    /** Counts cores that are switched off too, which the runtime's count leaves out. */
    private fun coreCount(): Int {
        val listed = File(CPU_DIR).listFiles { file -> CORE_NAME.matches(file.name) }?.size ?: 0
        return maxOf(listed, Runtime.getRuntime().availableProcessors())
    }

    /**
     * Records which core each of the app's threads runs on and how fast each core is clocked. Work a
     * thread did since the last sample is credited to the core it is on now, which is close enough
     * at this sampling rate to tell fast cores from efficiency cores.
     */
    private class CpuSampler(
        private val cores: Int,
    ) {
        private val lastTicks = HashMap<String, Long>()
        private val ticksByCore = HashMap<Int, Long>()
        private val maxKhz = (0 until cores).map { readLong("$CPU_DIR/cpu$it/cpufreq/cpuinfo_max_freq") }
        private val clockPercentSums = LongArray(cores)
        private var clockSamples = 0

        /** Emulators report placeholder top clocks below 1 MHz, which say nothing about the chip. */
        private var clocksReadable = maxKhz.all { it != null && it >= KHZ_PER_MHZ }

        fun sample() {
            File("/proc/self/task").listFiles()?.forEach { task ->
                val stat = runCatching { File(task, "stat").readText() }.getOrNull() ?: return@forEach
                val parsed = KokoroBenchmarkPlanning.parseTaskStat(stat) ?: return@forEach
                val previous = lastTicks.put(task.name, parsed.cpuTicks)
                val delta = if (previous == null) 0L else parsed.cpuTicks - previous
                if (delta > 0L) ticksByCore[parsed.core] = (ticksByCore[parsed.core] ?: 0L) + delta
            }
            if (!clocksReadable) return
            val current = (0 until cores).map { readLong("$CPU_DIR/cpu$it/cpufreq/scaling_cur_freq") }
            if (current.any { it == null }) {
                clocksReadable = false
                return
            }
            current.forEachIndexed { core, khz -> clockPercentSums[core] += khz!! * PERCENT / maxKhz[core]!! }
            clockSamples++
        }

        fun usage(
            cpuMs: Long,
            wallMs: Long,
        ): KokoroCpuUsage =
            KokoroCpuUsage(
                cpuMs = cpuMs,
                wallMs = wallMs,
                workPercentByCore = KokoroBenchmarkPlanning.percents(ticksByCore, cores),
                clockPercentByCore =
                    if (clocksReadable && clockSamples > 0) clockPercentSums.map { (it / clockSamples).toInt() } else emptyList(),
            )
    }

    private companion object {
        const val CPU_DIR = "/sys/devices/system/cpu"
        val CORE_NAME = Regex("cpu\\d+")
        const val SAMPLE_EVERY_MS = 100L
        const val MS_PER_SECOND = 1_000L
        const val KB_PER_MB = 1_024L
        const val KHZ_PER_MHZ = 1_000L
        const val BYTES_PER_MB = 1_024L * 1_024L
        const val PERCENT = 100L
        val THERMAL_NAMES = listOf("none", "light", "moderate", "severe", "critical", "emergency", "shutdown")
    }
}

private fun readLong(path: String): Long? = runCatching { File(path).readText().trim().toLong() }.getOrNull()
