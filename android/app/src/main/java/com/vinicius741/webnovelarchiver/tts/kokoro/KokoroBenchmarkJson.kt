package com.vinicius741.webnovelarchiver.tts.kokoro

import org.json.JSONArray
import org.json.JSONObject

/** The speed test's results as JSON, for analysing a shared report. */
internal object KokoroBenchmarkJson {
    private const val FORMAT_VERSION = 1

    fun json(result: KokoroBenchmarkResult): JSONObject {
        val setup = result.setup
        val device = setup.device
        return JSONObject()
            .put("format", FORMAT_VERSION)
            .put("appVersion", setup.appVersion)
            .put("startedAt", setup.startedAt)
            .put(
                "device",
                JSONObject()
                    .put("manufacturer", device.manufacturer)
                    .put("model", device.model)
                    .put("soc", device.soc ?: JSONObject.NULL)
                    .put("android", device.androidRelease)
                    .put("sdk", device.sdk)
                    .put("cores", device.cores)
                    .put("coreMaxMhz", JSONArray(device.coreMaxMhz))
                    .put("totalRamMb", device.totalRamMb),
            ).put("model", KokoroModel.DIR_NAME)
            .put("voice", setup.voiceName)
            .put("listeningRate", setup.listeningRate.toDouble())
            .put("appThreads", setup.appThreads)
            .put("narrationModelLoaded", setup.narrationModelLoaded)
            .put("atStart", headroomJson(setup.atStart))
            .put("atEnd", headroomJson(result.atEnd))
            .put("totalMs", result.totalMs)
            .put("cancelled", result.cancelled)
            .put("runs", JSONArray(result.runs.map(::runJson)))
    }

    private fun headroomJson(headroom: KokoroHeadroom): JSONObject =
        JSONObject()
            .put("availableRamMb", headroom.availableRamMb)
            .put("thermalStatus", headroom.thermalStatus)
            .put("thermalHeadroom", headroom.thermalHeadroom?.let { round(it.toDouble()) } ?: JSONObject.NULL)

    private fun runJson(run: KokoroBenchmarkRun): JSONObject =
        JSONObject()
            .put("engines", run.engines)
            .put("threads", run.threads)
            .put("loadMs", run.loadMs)
            .put("warmupMs", run.warmupMs)
            .put("modelMemoryMb", run.modelMemoryMb ?: JSONObject.NULL)
            .put("wallMs", run.wallMs)
            .put("audioMs", run.audioMs)
            .put("speed", round(run.speed))
            .put("error", run.error ?: JSONObject.NULL)
            .put("cpu", run.cpu?.let(::cpuJson) ?: JSONObject.NULL)
            .put(
                "sentences",
                JSONArray(
                    run.sentences.map {
                        JSONObject()
                            .put("chars", it.chars)
                            .put("audioMs", it.audioMs)
                            .put("generateMs", it.generateMs)
                            .put("speed", round(it.speed))
                    },
                ),
            )

    private fun cpuJson(cpu: KokoroCpuUsage): JSONObject =
        JSONObject()
            .put("cpuMs", cpu.cpuMs)
            .put("wallMs", cpu.wallMs)
            .put("coresBusy", round(cpu.coresBusy))
            .put("workPercentByCore", JSONArray(cpu.workPercentByCore))
            .put("clockPercentByCore", JSONArray(cpu.clockPercentByCore))

    private fun round(value: Double): Double = Math.round(value * 1000.0) / 1000.0
}
