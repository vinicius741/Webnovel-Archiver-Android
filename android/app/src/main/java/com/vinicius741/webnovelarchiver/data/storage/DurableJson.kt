package com.vinicius741.webnovelarchiver.data.storage

import android.util.AtomicFile
import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import timber.log.Timber
import java.io.File
import java.io.IOException

/**
 * Crash-safe JSON I/O: writes go through [AtomicFile] (temp + rename), reads distinguish
 * missing from corrupt, and corrupt files are quarantined to a `.corrupt` sibling for recovery.
 * Each document carries a schema/app-version envelope for future migrations.
 */
object DurableJson {
    /** Bumped whenever the on-disk shape of any durable JSON document changes. */
    const val CURRENT_SCHEMA_VERSION = 1

    data class Envelope(
        val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
        val appVersion: String? = null,
        val payload: Any? = null,
    )

    fun envelope(
        value: Any?,
        appVersion: String?,
    ): Envelope =
        Envelope(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            appVersion = appVersion,
            payload = value,
        )

    /** Atomic write: the previous document is replaced only after the new bytes are written and flushed. */
    @Suppress("TooGenericExceptionCaught") // Any write/serialize failure must route to failWrite; re-thrown after cleanup.
    fun writeAtomic(
        file: File,
        gson: Gson,
        envelope: Envelope,
    ) {
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        val out = atomic.startWrite()
        try {
            out.write(gson.toJson(envelope).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(out)
        } catch (error: Throwable) {
            // failWrite closes and discards the temp file; re-throw so the caller sees the failure.
            atomic.failWrite(out)
            throw error
        }
    }

    /**
     * Reads [file] and unwraps the payload. A missing file is a clean [DurableReadResult.Absent];
     * a corrupt file is logged and quarantined to a `.corrupt` sibling so it stays recoverable.
     */
    inline fun <reified T> readAtomicResult(
        file: File,
        gson: Gson,
        quarantineOnCorruption: Boolean = true,
    ): DurableReadResult<T> {
        if (!file.exists()) return DurableReadResult.Absent
        val text =
            try {
                val atomic = AtomicFile(file)
                String(atomic.readFully(), Charsets.UTF_8)
            } catch (error: IOException) {
                Timber.e(error, "DurableJson read failed for %s", file.name)
                return DurableReadResult.IoFailure(error)
            }
        return when (val decoded = decodeText<T>(text, gson)) {
            is DurableReadResult.Corrupt -> {
                if (quarantineOnCorruption) {
                    val quarantined = quarantineCorrupt(file, decoded.cause, reason = "parse")
                    decoded.copy(quarantinedFile = quarantined)
                } else {
                    Timber.e(decoded.cause, "DurableJson parse failed for %s", file.name)
                    decoded
                }
            }
            else -> decoded
        }
    }

    inline fun <reified T> readAtomic(
        file: File,
        gson: Gson,
    ): T? = (readAtomicResult<T>(file, gson) as? DurableReadResult.Present)?.value

    /** Pure decoder used by tests and by the AtomicFile adapter above. */
    inline fun <reified T> decodeText(
        text: String,
        gson: Gson,
    ): DurableReadResult<T> {
        val root =
            try {
                JsonParser.parseString(text)
            } catch (error: JsonParseException) {
                return DurableReadResult.Corrupt(error)
            } catch (error: IllegalStateException) {
                return DurableReadResult.Corrupt(error)
            }
        val payload =
            if (root.isJsonObject && root.asJsonObject.has("payload")) {
                val schema =
                    runCatching {
                        root.asJsonObject
                            .get("schemaVersion")
                            ?.takeIf { it.isJsonPrimitive }
                            ?.asInt
                    }.getOrNull()
                        ?: return DurableReadResult.Corrupt(JsonParseException("Envelope is missing schemaVersion"))
                if (schema != CURRENT_SCHEMA_VERSION) {
                    return DurableReadResult.UnsupportedSchema(schema, CURRENT_SCHEMA_VERSION)
                }
                root.asJsonObject.get("payload")
            } else {
                root
            }
        return try {
            val value =
                gson.fromJson<T>(payload, object : TypeToken<T>() {}.type)
                    ?: return DurableReadResult.Corrupt(JsonParseException("Decoded payload was null"))
            DurableReadResult.Present(value)
        } catch (error: JsonParseException) {
            DurableReadResult.Corrupt(error)
        } catch (error: IllegalStateException) {
            DurableReadResult.Corrupt(error)
        }
    }

    /** Renames [file] to a `.corrupt` sibling so a corrupt document is recoverable, not hidden. */
    @PublishedApi
    internal fun quarantineCorrupt(
        file: File,
        error: Throwable,
        reason: String,
    ): File? {
        Timber.e(error, "DurableJson %s failed for %s; quarantining to .corrupt", reason, file.name)
        return runCatching {
            if (!file.exists()) return@runCatching null
            val quarantine = File(file.parentFile, "${file.name}.corrupt")
            // Don't overwrite a prior quarantined copy; append a counter so every corrupt revision is kept.
            val target =
                if (!quarantine.exists()) {
                    quarantine
                } else {
                    var i = 1
                    while (File(file.parentFile, "${file.name}.corrupt.$i").exists()) i += 1
                    File(file.parentFile, "${file.name}.corrupt.$i")
                }
            if (file.renameTo(target)) target else null
        }.onFailure { Timber.w(it, "Could not quarantine corrupt file %s", file.name) }.getOrNull()
    }
}

sealed interface DurableReadResult<out T> {
    data class Present<T>(
        val value: T,
    ) : DurableReadResult<T>

    data object Absent : DurableReadResult<Nothing>

    data class Corrupt(
        val cause: Throwable,
        val quarantinedFile: File? = null,
    ) : DurableReadResult<Nothing>

    data class UnsupportedSchema(
        val foundVersion: Int,
        val supportedVersion: Int,
    ) : DurableReadResult<Nothing>

    data class IoFailure(
        val cause: IOException,
    ) : DurableReadResult<Nothing>
}
