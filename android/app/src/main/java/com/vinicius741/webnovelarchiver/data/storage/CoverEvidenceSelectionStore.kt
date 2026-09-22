package com.vinicius741.webnovelarchiver.data.storage

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

/**
 * Last automatic TypeSafe chapter selection per story — a display hint for the cover context
 * picker, not a manual override. Disposable and device-local like the evidence cache itself:
 * loss just means the picker shows the pre-scan empty state until the next generation.
 */
class CoverEvidenceSelectionStore(
    private val file: File,
) {
    private val selections: MutableMap<String, List<Int>> = load()

    @Synchronized
    fun selection(storyId: String): List<Int>? = selections[storyId]

    @Synchronized
    fun record(
        storyId: String,
        chapterIndices: List<Int>,
    ) {
        selections[storyId] = chapterIndices
        runCatching {
            file.parentFile?.mkdirs()
            val pending = File(file.parentFile, file.name + ".tmp")
            pending.writeText(serialize())
            check(pending.renameTo(file)) { "Could not save TypeSafe chapter selection" }
        }
    }

    private fun serialize(): String {
        val root = JsonObject()
        selections.forEach { (storyId, indices) ->
            val array = JsonArray()
            indices.forEach(array::add)
            root.add(storyId, array)
        }
        return root.toString()
    }

    private fun load(): MutableMap<String, List<Int>> =
        runCatching {
            if (!file.exists()) return mutableMapOf()
            val root = JsonParser.parseString(file.readText()).takeIf { it.isJsonObject }?.asJsonObject
            val parsed = root ?: return mutableMapOf()
            val result = mutableMapOf<String, List<Int>>()
            for ((storyId, value) in parsed.entrySet()) {
                val indices =
                    value
                        .takeIf { it.isJsonArray }
                        ?.asJsonArray
                        ?.mapNotNull { element -> runCatching { element.asInt }.getOrNull() }
                        .orEmpty()
                if (indices.isNotEmpty()) result[storyId] = indices
            }
            result
        }.getOrDefault(mutableMapOf())
}
