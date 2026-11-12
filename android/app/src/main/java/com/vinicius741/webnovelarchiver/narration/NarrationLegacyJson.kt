package com.vinicius741.webnovelarchiver.narration

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Release builds 1.0.18-native and 1.0.19-native wrote narration files with R8-renamed keys
 * (`"a"`, `"b"`, …), because `proguard-rules.pro` did not keep these models. Both releases renamed
 * them identically (checked against their R8 mappings): each class's fields became `a`, `b`, `c`… in
 * declaration order, and `NarrationPackIndex.format` was not written at all. These tables translate
 * such files to the real names, so queues and generated chapters from those releases still load.
 * Files written since then already use the real names and pass through unchanged.
 */
internal object NarrationLegacyJson {
    private val VOICE = listOf("engine", "voiceId", "pitch", "label")
    private val JOB = listOf("id", "storyId", "chapterId", "voice", "origin", "state", "error", "version", "enqueuedAt")
    private val QUEUE = listOf("jobs", "paused", "pauseReason", "keepAhead", "msPerChar", "speed", "screenOffPromptShown")
    private val RECIPE = listOf("storyId", "chapterId", "chunkingVersion", "textFingerprint", "title", "sentences", "voice", "voiceKey")
    private val INDEX = listOf("codec", "sampleRate", "sentenceDurationsMs", "titleDurationMs", "totalDurationMs", "bytes", "generatedAt")

    fun queue(text: String): String =
        upgrade(text) { document ->
            rename(document, QUEUE).also { queue ->
                queue.get("jobs")?.takeIf(JsonElement::isJsonArray)?.asJsonArray?.forEachIndexed { index, job ->
                    if (job.isJsonObject) queue.getAsJsonArray("jobs").set(index, job(job.asJsonObject))
                }
            }
        }

    fun recipe(text: String): String = upgrade(text) { recipe -> rename(recipe, RECIPE).also { voiceIn(it) } }

    fun index(text: String): String = upgrade(text) { index -> rename(index, INDEX) }

    private fun job(job: JsonObject): JsonObject = rename(job, JOB).also { voiceIn(it) }

    private fun voiceIn(owner: JsonObject) {
        owner.get("voice")?.takeIf(JsonElement::isJsonObject)?.let { owner.add("voice", rename(it.asJsonObject, VOICE)) }
    }

    /** Parses [text], translates it when it is in the legacy form, and serializes it again. */
    private inline fun upgrade(
        text: String,
        translate: (JsonObject) -> JsonObject,
    ): String {
        val root = runCatching { JsonParser.parseString(text) }.getOrNull()
        if (root == null || !root.isJsonObject || !isLegacy(root.asJsonObject)) return text
        return translate(root.asJsonObject).toString()
    }

    /** Legacy objects carry only one-letter keys; current ones carry the real names. */
    private fun isLegacy(json: JsonObject): Boolean = json.size() > 0 && json.keySet().all { it.length == 1 && it[0] in 'a'..'z' }

    /** Renames `a`, `b`, … to [names] in order; anything else (already-real names) is kept. */
    private fun rename(
        json: JsonObject,
        names: List<String>,
    ): JsonObject {
        if (!isLegacy(json)) return json
        val renamed = JsonObject()
        json.entrySet().forEach { (key, value) ->
            val position = key[0] - 'a'
            renamed.add(names.getOrNull(position) ?: key, value)
        }
        return renamed
    }
}
