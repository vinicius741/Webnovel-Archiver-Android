package com.vinicius741.webnovelarchiver.simulation

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.vinicius741.webnovelarchiver.app.AppTime
import com.vinicius741.webnovelarchiver.data.storage.AtomicFileWrites
import java.io.File

/** Bounded, durable request cursors and sanitized evidence. No bodies, headers, or credentials. */
internal class SimulationJournal(
    val directory: File,
    private val config: SimulationConfig,
    private val time: AppTime,
) {
    private val file = File(directory, "report.json")
    private val report = if (file.exists()) JsonParser.parseString(file.readText()).asJsonObject else JsonObject()
    private val counts = report.getAsJsonObject("attempts") ?: JsonObject()
    private val events = report.getAsJsonArray("events") ?: JsonArray()
    private val verified = report.getAsJsonArray("verifiedHosts") ?: JsonArray()
    private var sequence = report.get("sequence")?.asLong ?: 0L

    init {
        directory.mkdirs()
        require(report.get("scenario")?.asString?.let { it == config.scenario.id } != false) { "Session scenario changed" }
        require(report.get("seed")?.asLong?.let { it == config.seed } != false) { "Session seed changed" }
        require(report.get("epochMillis")?.asLong?.let { it == config.epochMillis } != false) { "Session epoch changed" }
        require(report.get("speed")?.asDouble?.let { it == config.speed } != false) { "Session speed changed" }
        report.addProperty("version", 1)
        report.addProperty("fixtureVersion", 1)
        report.addProperty("bootCount", (report.get("bootCount")?.asInt ?: 0) + 1)
        report.addProperty("sessionId", config.sessionId)
        report.addProperty("scenario", config.scenario.id)
        report.addProperty("seed", config.seed)
        report.addProperty("timing", config.timing)
        report.addProperty("speed", config.speed)
        report.addProperty("epochMillis", config.epochMillis)
        if (!report.has("phase")) report.addProperty("phase", "starting")
        if (!report.has("startedActualMillis")) report.addProperty("startedActualMillis", System.currentTimeMillis())
        if (!report.has("startedLogicalMillis")) report.addProperty("startedLogicalMillis", time.nowMillis())
        if (report.get("phase")?.asString in setOf("running", "held", "waiting_verification")) {
            event("process_interrupted", "session")
            report.addProperty("phase", "interrupted")
        }
        persist()
    }

    @Synchronized fun nextAttempt(key: String): Int {
        require(counts.size() < 512 || counts.has(key)) { "Simulation request key budget exceeded" }
        val next = (counts.get(key)?.asInt ?: 0) + 1
        counts.addProperty(key, next)
        persist()
        return next
    }

    @Synchronized fun event(
        kind: String,
        operation: String,
        result: String? = null,
    ) {
        sequence += 1
        events.add(
            JsonObject().apply {
                addProperty("sequence", sequence)
                addProperty("kind", kind)
                addProperty("operation", operation)
                addProperty("logicalMillis", time.nowMillis())
                addProperty("actualMillis", System.currentTimeMillis())
                result?.let { addProperty("result", it) }
            },
        )
        while (events.size() > 400) events.remove(0)
        if (kind == "fault") report.addProperty("faultObserved", true)
        if (kind == "denied") {
            val denied = (report.get("deniedRequests")?.asInt ?: 0) + 1
            report.addProperty("deniedRequests", denied)
            if (denied > config.scenario.expectedDenials) {
                report.addProperty("passed", false)
                report.addProperty("phase", "failed")
                val assertions = report.getAsJsonObject("assertions") ?: JsonObject()
                assertions.addProperty("noUnexpectedRequests", false)
                report.add("assertions", assertions)
            }
        }
        persist()
    }

    @Synchronized fun phase(value: String) {
        if (value in setOf("completed", "failed")) updateDurations()
        report.addProperty("phase", value)
        persist()
    }

    @Synchronized fun phase(): String = report.get("phase")?.asString ?: "starting"

    @Synchronized fun verified(host: String): Boolean = verified.any { it.asString == host }

    @Synchronized fun verify(host: String) {
        if (!verified(host)) verified.add(host)
        event("verification", "source", "verified")
    }

    @Synchronized fun autoStarted(): Boolean = report.get("autoStarted")?.asBoolean == true

    @Synchronized fun markAutoStarted() {
        report.addProperty("autoStarted", true)
        phase("running")
    }

    @Synchronized fun summary(value: JsonObject) {
        report.getAsJsonObject("summary")?.get("actualDurationMillis")?.let { value.add("actualDurationMillis", it) }
        report.add("summary", value)
        persist()
    }

    @Synchronized fun assertions(values: Map<String, Boolean>) {
        report.add("assertions", JsonObject().apply { values.forEach { (key, ok) -> addProperty(key, ok) } })
        report.addProperty("passed", values.values.all { it })
        phase(if (values.values.all { it }) "completed" else "failed")
    }

    @Synchronized fun faultObserved(): Boolean = report.get("faultObserved")?.asBoolean == true

    @Synchronized fun deniedCount(): Int = report.get("deniedRequests")?.asInt ?: 0

    private fun persist() {
        report.add("attempts", counts)
        report.add("events", events)
        report.add("verifiedHosts", verified)
        report.addProperty("sequence", sequence)
        AtomicFileWrites.writeText(file, report.toString())
    }

    private fun updateDurations() {
        report.addProperty("actualDurationMillis", System.currentTimeMillis() - report.get("startedActualMillis").asLong)
        report.addProperty("logicalDurationMillis", time.nowMillis() - report.get("startedLogicalMillis").asLong)
    }
}
