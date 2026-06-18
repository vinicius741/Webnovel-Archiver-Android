package com.vinicius741.webnovelarchiver.core

import android.util.AtomicFile
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * Crash-safe JSON I/O helpers used by [AppStorage].
 *
 * Goals (Reliability R1):
 *  - Every durable JSON document is written through [AtomicFile], which writes to a temporary
 *    sibling file and renames it into place. A process death mid-write leaves the previous file
 *    intact instead of a truncated half-file.
 *  - Each durable document carries a small envelope (`schemaVersion` + `appVersion`) so future
 *    migrations can detect the on-disk shape.
 */
object DurableJson {
    /** Bumped whenever the on-disk shape of any durable JSON document changes. */
    const val CURRENT_SCHEMA_VERSION = 1

    data class Envelope(
        val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
        val appVersion: String? = null,
        val payload: Any? = null,
    )

    /** Wraps a value in an [Envelope] so the on-disk shape is uniform across documents. */
    fun envelope(
        value: Any?,
        appVersion: String?,
    ): Envelope =
        Envelope(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            appVersion = appVersion,
            payload = value,
        )

    /**
     * Atomic write of [envelope] to [file] (JSON via [gson]). Overwrites the previous document only
     * after the new bytes are fully written and flushed, via [AtomicFile.finishWrite].
     */
    fun writeAtomic(
        file: File,
        gson: Gson,
        envelope: Envelope,
    ) {
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        val out = atomic.startWrite()
        out.use {
            it.write(gson.toJson(envelope).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(it)
        }
    }

    /** Reads [file] and returns the unwrapped payload of type [T], or null if missing/corrupt. */
    inline fun <reified T> readAtomic(
        file: File,
        gson: Gson,
    ): T? {
        if (!file.exists()) return null
        val atomic = AtomicFile(file)
        val bytes = runCatching { atomic.readFully() }.getOrNull() ?: return null
        val text = String(bytes, Charsets.UTF_8)
        // New envelope shape carries a "payload" key; pre-migration files are bare payloads.
        return if (text.contains("\"payload\"")) {
            val envelope = runCatching { gson.fromJson<Envelope>(text, object : TypeToken<Envelope>() {}.type) }.getOrNull()
            val payloadJson = envelope?.payload?.let { gson.toJson(it) } ?: text
            runCatching { gson.fromJson<T>(payloadJson, object : TypeToken<T>() {}.type) }.getOrNull()
        } else {
            runCatching { gson.fromJson<T>(text, object : TypeToken<T>() {}.type) }.getOrNull()
        }
    }
}
