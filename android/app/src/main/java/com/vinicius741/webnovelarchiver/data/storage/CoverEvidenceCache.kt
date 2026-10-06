package com.vinicius741.webnovelarchiver.data.storage

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

/** Disposable, device-local score cache. Contains no API keys or chapter text; excluded from backups. */
internal class CoverEvidenceCache(
    private val directory: File,
) {
    @Synchronized
    fun read(key: String): JsonObject? =
        runCatching {
            val file = File(directory, key)
            if (!file.exists() || file.length() > 64_000) return null
            JsonParser.parseString(file.readText()).asJsonObject
        }.getOrNull()

    @Synchronized
    fun write(
        key: String,
        value: JsonObject,
    ) {
        AtomicFileWrites.writeText(File(directory, key), value.toString())
    }

    @Synchronized
    fun trim() {
        directory
            .listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(20_000)
            ?.forEach { it.delete() }
    }
}
