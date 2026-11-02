package com.vinicius741.webnovelarchiver.simulation

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.Reader

internal data class SimulationScenario(
    val id: String,
    val action: String,
    val screen: String,
    val faults: Map<String, String>,
    val manual: Boolean = false,
    val missingKey: Boolean = false,
    val archived: Boolean = false,
    val expectedFailure: Boolean = false,
    val repeat: Int = 1,
    val chapterCount: Int = 6,
    val expectedDenials: Int = 0,
)

internal data class SimulationConfig(
    val sessionId: String,
    val scenario: SimulationScenario,
    val seed: Long,
    val timing: String,
    val speed: Double,
    val epochMillis: Long,
    val hold: String?,
    val autoStart: Boolean,
) {
    companion object {
        private val identifier = Regex("[a-zA-Z0-9][a-zA-Z0-9_-]{0,79}")
        private val stages = setOf("decisions", "description", "prompt", "image", "source")

        fun parse(
            config: Reader,
            catalog: Reader,
        ): SimulationConfig {
            val root = JsonParser.parseReader(config).asJsonObject
            require(
                root.keySet().all {
                    it in
                        setOf("version", "sessionId", "scenario", "seed", "timing", "speed", "epochMillis", "hold", "autoStart")
                },
            )
            require(root.get("version").asInt == 1) { "Unsupported simulation config" }
            val id = root.get("sessionId").asString
            require(identifier.matches(id)) { "Invalid simulation session id" }
            val scenarios = JsonParser.parseReader(catalog).asJsonObject
            require(scenarios.get("version").asInt == 1)
            val scenario =
                scenarios
                    .getAsJsonArray(
                        "scenarios",
                    ).single { it.asJsonObject.get("id").asString == root.get("scenario").asString }
                    .asJsonObject
            val timing = root.get("timing")?.asString ?: "fast"
            require(timing in setOf("fast", "visible", "controlled"))
            val speed = root.get("speed")?.asDouble ?: if (timing == "fast") 100.0 else 1.0
            require(speed.isFinite() && speed in 1.0..1000.0)
            val epoch = root.get("epochMillis").asLong
            require(epoch in 1_577_836_800_000L..4_102_444_800_000L)
            val hold = root.get("hold")?.takeUnless { it.isJsonNull }?.asString
            require(hold == null || hold in stages)
            require(timing != "controlled" || hold != null) { "Controlled timing requires a hold stage" }
            return SimulationConfig(
                id,
                scenario.toScenario(),
                root.get("seed")?.asLong ?: 1L,
                timing,
                speed,
                epoch,
                hold,
                root.get("autoStart")?.asBoolean ?: true,
            )
        }

        private fun JsonObject.toScenario(): SimulationScenario =
            SimulationScenario(
                id = get("id").asString,
                action = get("action").asString,
                screen = get("screen").asString,
                faults = getAsJsonObject("faults")?.entrySet()?.associate { it.key to it.value.asString }.orEmpty(),
                manual = get("manual")?.asBoolean ?: false,
                missingKey = get("missingKey")?.asBoolean ?: false,
                archived = get("archived")?.asBoolean ?: false,
                expectedFailure = get("expectedFailure")?.asBoolean ?: false,
                repeat = get("repeat")?.asInt ?: 1,
                chapterCount = get("chapterCount")?.asInt ?: 6,
                expectedDenials = get("expectedDenials")?.asInt ?: 0,
            )
    }
}
