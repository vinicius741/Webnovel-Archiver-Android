package com.vinicius741.webnovelarchiver.tts.kokoro

import com.vinicius741.webnovelarchiver.tts.kokoro.KokoroBenchmarkPlanning.seconds
import com.vinicius741.webnovelarchiver.tts.kokoro.KokoroBenchmarkPlanning.times
import java.util.Locale

/** The speed test's results as text to read on the phone and to share, with the data at the end. */
internal object KokoroBenchmarkReport {
    const val DATA_MARKER = "--- data ---"

    fun report(result: KokoroBenchmarkResult): String =
        buildString {
            appendHeader(result)
            appendLine()
            appendConclusion(result)
            appendLine()
            appendRuns(result.runs)
            appendLine()
            appendHeadroom(result)
            appendLine()
            appendLine("Speed is seconds of audio made per second. Below your listening speed, playback has to wait.")
            appendLine("Cores busy is CPU time over real time: 8.0 on an 8-core phone would mean every core fully used.")
            appendLine()
            appendLine(DATA_MARKER)
            append(KokoroBenchmarkJson.json(result).toString(2))
        }

    private fun StringBuilder.appendHeader(result: KokoroBenchmarkResult) {
        val setup = result.setup
        val device = setup.device
        appendLine("Kokoro speed test")
        appendLine("App ${setup.appVersion} · ${setup.startedAt}")
        appendLine(
            "Phone: ${device.manufacturer} ${device.model}${device.soc?.let { " ($it)" }.orEmpty()} · " +
                "Android ${device.androidRelease} (API ${device.sdk}) · ${device.cores} cores · ${gigabytes(device.totalRamMb)} RAM",
        )
        appendLine("Model: ${KokoroModel.DIR_NAME} · voice ${setup.voiceName} · listening speed ${times(setup.listeningRate.toDouble())}")
        appendLine("Narration model also loaded during the test: ${if (setup.narrationModelLoaded) "yes" else "no"}")
        appendLine("Took ${seconds(result.totalMs)}${if (result.cancelled) " (stopped early)" else ""}")
    }

    private fun StringBuilder.appendConclusion(result: KokoroBenchmarkResult) {
        val setup = result.setup
        val app = result.runs.firstOrNull { it.engines == 1 && it.threads == setup.appThreads && it.completed }
        appendLine(KokoroBenchmarkPlanning.verdict(app, setup.listeningRate))
        val best = KokoroBenchmarkPlanning.fastest(result.runs)
        if (best != null && app != null && best.label != app.label && app.speed > 0.0) {
            appendLine(
                "Fastest: ${best.label} at ${times(best.speed)}, ${times(best.speed / app.speed)} the app's setting.",
            )
        }
    }

    private fun StringBuilder.appendRuns(runs: List<KokoroBenchmarkRun>) {
        appendLine("Setup · load · speed · cores busy · slowest sentence · first sentence")
        runs.forEach { run ->
            if (!run.completed) {
                appendLine("${run.label} · ${run.error ?: "no result"}")
            } else {
                val busy = run.cpu?.let { String.format(Locale.US, "%.1f", it.coresBusy) } ?: "-"
                val first = run.sentences.firstOrNull()?.let { seconds(it.generateMs) } ?: "-"
                appendLine("${run.label} · ${seconds(run.loadMs)} · ${times(run.speed)} · $busy · ${times(run.slowestSpeed)} · $first")
            }
        }
    }

    private fun StringBuilder.appendHeadroom(result: KokoroBenchmarkResult) {
        val setup = result.setup
        val device = setup.device
        appendLine("Headroom")
        val app = result.runs.firstOrNull { it.engines == 1 && it.threads == setup.appThreads && it.completed }
        app?.cpu?.let { cpu ->
            val share = if (device.cores > 0) Math.round(cpu.coresBusy * 100 / device.cores) else 0
            appendLine(
                "CPU: the app's setting kept ${String.format(Locale.US, "%.1f", cpu.coresBusy)} of ${device.cores} cores busy ($share%).",
            )
            appendCoreDetail(cpu, device.coreMaxMhz)
        }
        appendMemory(result)
        appendLine(
            "Heat: ${setup.atStart.thermalStatus} at start (headroom ${headroom(setup.atStart.thermalHeadroom)}), " +
                "${result.atEnd.thermalStatus} at end (headroom ${headroom(result.atEnd.thermalHeadroom)}). " +
                "Headroom 1.0 is where the phone starts slowing itself down.",
        )
        KokoroBenchmarkPlanning.throttleNote(result.runs, setup.appThreads)?.let { appendLine(it) }
    }

    private fun StringBuilder.appendCoreDetail(
        cpu: KokoroCpuUsage,
        coreMaxMhz: List<Int>,
    ) {
        val groups = KokoroBenchmarkPlanning.clusters(coreMaxMhz)
        if (groups.size > 1 && cpu.workPercentByCore.isNotEmpty()) {
            val parts =
                groups.map { (cores, mhz) ->
                    val work = cores.sumOf { cpu.workPercentByCore.getOrElse(it) { 0 } }
                    "${coreNames(cores)} ($mhz MHz) $work%"
                }
            appendLine("Where the work ran: ${parts.joinToString(", ")}.")
        } else if (cpu.workPercentByCore.isNotEmpty()) {
            appendLine("Work by core (%): ${cpu.workPercentByCore.joinToString(", ")}")
        }
        if (cpu.clockPercentByCore.isNotEmpty()) {
            appendLine("Average clock by core (% of top): ${cpu.clockPercentByCore.joinToString(", ")}")
        }
        if (coreMaxMhz.isNotEmpty()) appendLine("Top clock by core (MHz): ${coreMaxMhz.joinToString(", ")}")
    }

    private fun StringBuilder.appendMemory(result: KokoroBenchmarkResult) {
        val free = result.setup.atStart.availableRamMb
        val model = result.runs.firstNotNullOfOrNull { it.modelMemoryMb?.takeIf { mb -> mb > 0L } }
        val copies = model?.let { " (room for about ${free / it} copies)" }.orEmpty()
        appendLine(
            "Memory: ${gigabytes(free)} free at start, ${gigabytes(result.atEnd.availableRamMb)} at end; " +
                "one model copy uses about ${model?.let { "$it MB" } ?: "an unknown amount"}$copies.",
        )
    }

    private fun coreNames(cores: List<Int>): String =
        if (cores.size == 1) {
            "core ${cores.first()}"
        } else if (cores == (cores.first()..cores.last()).toList()) {
            "cores ${cores.first()}-${cores.last()}"
        } else {
            "cores ${cores.joinToString(",")}"
        }

    private fun headroom(value: Float?): String = value?.let { String.format(Locale.US, "%.2f", it) } ?: "not reported"

    private fun gigabytes(mb: Long): String = String.format(Locale.US, "%.1f GB", mb / 1024.0)
}
