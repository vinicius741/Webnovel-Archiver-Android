package com.vinicius741.webnovelarchiver.ai

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.delay
import java.io.IOException

/**
 * Typed chapter judgments from any OpenRouter decision model (Jev, Clef, d1, ...) through the
 * shared Decisions API client. Rubrics build their questions with [scoreQuestion]/[noulQuestion]
 * and read answers with [answer]; wire fields remain explicit for R8.
 */
internal class DecisionClient(
    private val openRouter: OpenRouterClient,
) {
    suspend fun evaluate(
        apiKey: String,
        body: JsonObject,
        onReceipt: suspend (JsonObject, Int) -> Unit = { _, _ -> },
    ): JsonObject {
        repeat(3) { attempt ->
            val (json, code) = openRouter.submitDecisions(apiKey, body)
            onReceipt(json, code)
            if (code in listOf(429, 503, 529) && attempt < 2) {
                delay(1_000L shl attempt)
            } else {
                checkResponse(code, body.get("model")?.asString)
                return json
            }
        }
        error("OpenRouter decision request failed")
    }

    private fun checkResponse(
        code: Int,
        model: String?,
    ) {
        val message =
            when (code) {
                in 200..299 -> return
                401, 403 -> "Invalid OpenRouter API key. Check Settings → AI Settings."
                402 -> "OpenRouter reports insufficient credits for this API key."
                404 ->
                    "Decision model ${model.orEmpty()} is unavailable on OpenRouter. " +
                        "Choose another chapter selection model in AI Controls or select chapters manually."
                else -> "OpenRouter chapter selection failed (HTTP $code). Completed chapter scores are saved; try again."
            }
        throw IOException(message)
    }

    companion object {
        /** Ordered score levels; answers arrive in 0..[SCORE_MAX] and are normalized to 0..1. */
        const val SCORE_MAX = 3.0

        fun request(
            state: JsonObject,
            model: String,
            questions: JsonObject,
        ): JsonObject =
            JsonObject().apply {
                addProperty("model", model)
                add("state", state)
                add("questions", questions)
            }

        fun scoreQuestion(
            instructions: String,
            levels: List<String>,
        ): JsonObject =
            JsonObject().apply {
                addProperty("type", "score")
                addProperty("instructions", instructions)
                add("criteria", JsonArray().apply { levels.forEach(::add) })
            }

        fun noulQuestion(
            instructions: String,
            whenTrue: String,
            whenFalse: String,
        ): JsonObject =
            JsonObject().apply {
                addProperty("type", "noul")
                addProperty("instructions", instructions)
                add(
                    "criteria",
                    JsonObject().apply {
                        addProperty("true", whenTrue)
                        addProperty("false", whenFalse)
                    },
                )
            }

        /** Normalized 0..1 answer; the type discriminator is checked when present, the typed field is required. */
        fun answer(
            json: JsonObject,
            name: String,
            type: String,
            max: Double,
        ): Double {
            val answers =
                json.get("answers")?.takeIf { it.isJsonObject }?.asJsonObject
                    ?: throw IOException("The decision model returned no chapter judgments")
            val answer = answers.get(name)?.takeIf { it.isJsonObject }?.asJsonObject
            val number = runCatching { answer?.get(type)?.asDouble }.getOrNull()
            val declared = answer?.get("type")?.takeIf { it.isJsonPrimitive }?.asString
            if ((declared != null && declared != type) || number == null || !number.isFinite() || number !in 0.0..max) {
                throw IOException("The decision model returned an invalid $name judgment")
            }
            return number / max
        }

        fun score(
            json: JsonObject,
            name: String,
        ): Double = answer(json, name, "score", SCORE_MAX)

        fun noul(
            json: JsonObject,
            name: String,
        ): Double = answer(json, name, "noul", 1.0)
    }
}
