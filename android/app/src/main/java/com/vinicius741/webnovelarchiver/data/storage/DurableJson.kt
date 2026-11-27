package com.vinicius741.webnovelarchiver.data.storage

import android.util.AtomicFile
import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import timber.log.Timber
import java.io.BufferedWriter
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Reader
import java.io.StringReader
import java.lang.reflect.Type

/**
 * Crash-safe JSON I/O: writes go through [AtomicFile] (temp + rename), reads distinguish
 * missing from corrupt, and corrupt files are quarantined to a `.corrupt` sibling for recovery.
 * Each document carries a schema/app-version envelope for future migrations.
 */
object DurableJson {
    /** Bumped whenever the on-disk shape of any durable JSON document changes. */
    const val CURRENT_SCHEMA_VERSION = 1

    private const val WRITE_BUFFER_CHARS = 16 * 1024

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
            writeEnvelope(out, gson, envelope)
            atomic.finishWrite(out)
        } catch (error: Throwable) {
            // failWrite closes and discards the temp file; re-throw so the caller sees the failure.
            atomic.failWrite(out)
            throw error
        }
    }

    /**
     * Streams [envelope] into [out] as UTF-8 JSON without building the document as a String.
     * `schemaVersion` is written first so [decodeStreamOfType] can decode the payload in one pass
     * (reflective Gson on ART orders fields by name, which put it after the payload). Flushes but
     * does not close [out]: [AtomicFile.finishWrite] still has to sync it.
     */
    fun writeEnvelope(
        out: OutputStream,
        gson: Gson,
        envelope: Envelope,
    ) {
        val writer = gson.newJsonWriter(BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8), WRITE_BUFFER_CHARS))
        writer.beginObject()
        writer.name("schemaVersion").value(envelope.schemaVersion.toLong())
        envelope.appVersion?.let { writer.name("appVersion").value(it) }
        envelope.payload?.let { payload ->
            writer.name("payload")
            gson.toJson(payload, payload.javaClass, writer)
        }
        writer.endObject()
        writer.flush()
    }

    /**
     * Reads [file] and unwraps the payload. A missing file is a clean [DurableReadResult.Absent];
     * a corrupt file is logged and quarantined to a `.corrupt` sibling so it stays recoverable.
     */
    inline fun <reified T> readAtomicResult(
        file: File,
        gson: Gson,
        quarantineOnCorruption: Boolean = true,
    ): DurableReadResult<T> = readAtomicResultOfType(file, gson, object : TypeToken<T>() {}.type, quarantineOnCorruption)

    /** [readAtomicResult] for callers that only hold a [Type] (e.g. a generic document wrapper). */
    fun <T> readAtomicResultOfType(
        file: File,
        gson: Gson,
        type: Type,
        quarantineOnCorruption: Boolean = true,
    ): DurableReadResult<T> {
        if (!file.exists()) return DurableReadResult.Absent
        val atomic = AtomicFile(file)
        return when (val decoded = decodeStreamOfType<T>(gson, type) { atomic.openRead() }) {
            is DurableReadResult.IoFailure -> {
                Timber.e(decoded.cause, "DurableJson read failed for %s", file.name)
                decoded
            }
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

    /** Pure in-memory decoder used by tests; files go through [decodeStreamOfType]. */
    inline fun <reified T> decodeText(
        text: String,
        gson: Gson,
    ): DurableReadResult<T> = decodeTextOfType(text, gson, object : TypeToken<T>() {}.type)

    /** [decodeText] for callers that only hold a [Type] (e.g. a generic document wrapper). */
    fun <T> decodeTextOfType(
        text: String,
        gson: Gson,
        type: Type,
    ): DurableReadResult<T> = decodeReaderOfType(gson, type) { StringReader(text) }

    /**
     * Streaming decoder over a byte source. [open] is called once per pass (a second pass happens
     * only for a legacy bare document or an envelope that lists `payload` before `schemaVersion`)
     * and every stream is closed. A failure of the source itself is an [DurableReadResult.IoFailure],
     * never [DurableReadResult.Corrupt], so a disk error does not quarantine a good document.
     */
    fun <T> decodeStreamOfType(
        gson: Gson,
        type: Type,
        open: () -> InputStream,
    ): DurableReadResult<T> {
        val source = TrackedSource(open)
        val decoded = decodeReaderOfType<T>(gson, type, source::reader)
        return source.failure?.let { DurableReadResult.IoFailure(it) } ?: decoded
    }

    @Suppress("NestedBlockDepth") // The streaming envelope reader handles keys without building a JSON tree.
    private fun <T> decodeReaderOfType(
        gson: Gson,
        type: Type,
        open: () -> Reader,
    ): DurableReadResult<T> {
        return try {
            JsonReader(open()).use { reader ->
                if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                    return@use decodeLegacy<T>(gson, type, open)
                }
                reader.beginObject()
                var schema: Int? = null
                var hasPayload = false
                var deferredPayload = false
                var payload: T? = null
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "schemaVersion" -> schema = reader.nextInt()
                        "payload" -> {
                            hasPayload = true
                            if (schema == null) {
                                // An envelope may put payload first. Inspect the schema before
                                // decoding a shape that this version may not understand.
                                deferredPayload = true
                                reader.skipValue()
                            } else if (schema != CURRENT_SCHEMA_VERSION) {
                                reader.skipValue()
                            } else {
                                payload = gson.fromJson(reader, type)
                            }
                        }
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
                check(reader.peek() == JsonToken.END_DOCUMENT) { "Trailing content after JSON document" }
                if (!hasPayload) {
                    decodeLegacy(gson, type, open)
                } else if (schema == null) {
                    DurableReadResult.Corrupt(JsonParseException("Envelope is missing schemaVersion"))
                } else if (schema != CURRENT_SCHEMA_VERSION) {
                    DurableReadResult.UnsupportedSchema(schema, CURRENT_SCHEMA_VERSION)
                } else {
                    if (deferredPayload) {
                        JsonReader(open()).use { retry ->
                            retry.beginObject()
                            while (retry.hasNext()) {
                                if (retry.nextName() == "payload") {
                                    payload = gson.fromJson(retry, type)
                                    break
                                }
                                retry.skipValue()
                            }
                        }
                    }
                    if (payload == null) {
                        DurableReadResult.Corrupt(JsonParseException("Decoded payload was null"))
                    } else {
                        DurableReadResult.Present(payload)
                    }
                }
            }
        } catch (error: JsonParseException) {
            DurableReadResult.Corrupt(error)
        } catch (error: IllegalStateException) {
            DurableReadResult.Corrupt(error)
        } catch (error: IOException) {
            DurableReadResult.Corrupt(error)
        } catch (error: NumberFormatException) {
            DurableReadResult.Corrupt(error)
        }
    }

    /** A document without an envelope is the payload itself (files written before the envelope). */
    private fun <T> decodeLegacy(
        gson: Gson,
        type: Type,
        open: () -> Reader,
    ): DurableReadResult<T> {
        val legacy = open().use { gson.fromJson<T>(it, type) }
        return if (legacy == null) {
            DurableReadResult.Corrupt(JsonParseException("Decoded payload was null"))
        } else {
            DurableReadResult.Present(legacy)
        }
    }

    /** Opens UTF-8 readers over [open] and remembers the first I/O failure the source itself raised. */
    private class TrackedSource(
        private val open: () -> InputStream,
    ) {
        var failure: IOException? = null
            private set

        fun reader(): Reader {
            val stream =
                try {
                    open()
                } catch (error: IOException) {
                    failure = failure ?: error
                    throw error
                }
            return InputStreamReader(FailureTrackingInputStream(stream), Charsets.UTF_8)
        }

        private inner class FailureTrackingInputStream(
            inner: InputStream,
        ) : FilterInputStream(inner) {
            override fun read(): Int = tracked { super.read() }

            override fun read(
                b: ByteArray,
                off: Int,
                len: Int,
            ): Int = tracked { super.read(b, off, len) }

            override fun skip(n: Long): Long = tracked { super.skip(n) }

            private inline fun <R> tracked(block: () -> R): R =
                try {
                    block()
                } catch (error: IOException) {
                    failure = failure ?: error
                    throw error
                }
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
