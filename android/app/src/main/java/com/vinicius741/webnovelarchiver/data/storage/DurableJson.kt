package com.vinicius741.webnovelarchiver.data.storage

import android.util.AtomicFile
import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.google.gson.reflect.TypeToken
import com.vinicius741.webnovelarchiver.ui.text
import java.io.File
import java.io.IOException
import timber.log.Timber

/**
 * Crash-safe JSON I/O helpers used by [AppStorage].
 *
 * Goals (Reliability R1):
 *  - Every durable JSON document is written through [AtomicFile], which writes to a temporary
 *    sibling file and renames it into place. A process death mid-write leaves the previous file
 *    intact instead of a truncated half-file.
 *  - Each durable document carries a small envelope (`schemaVersion` + `appVersion`) so future
 *    migrations can detect the on-disk shape.
 *
 * Tier 1 durability fixes (P1/P2):
 *  - Writes route a serialization/IO failure through [AtomicFile.failWrite] so the half-written
 *    new file is discarded and no stale `.new` artifact is left behind (P1).
 *  - Reads distinguish "file missing" (a clean null) from "parse failed". A corrupt document is
 *    logged and quarantined to a sibling `.corrupt` file so the data is not silently hidden and can
 *    be recovered, instead of vanishing from the user's view (P2).
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
     *
     * P1: a failure during serialization or the underlying write now goes through
     * [AtomicFile.failWrite] (which closes + discards the temp `.new` file) and re-throws, so a
     * failed write can never leave a stale `.new` artifact behind and the caller gets a real signal.
     */
    @Suppress("TooGenericExceptionCaught") // P1: any write/serialize failure must route to failWrite; re-thrown after cleanup.
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
            // failWrite closes the stream and deletes the half-written new file. Re-throw so the
            // caller observes a real failure instead of a silent stale-artifact-and-no-commit.
            atomic.failWrite(out)
            throw error
        }
    }

    /**
     * Reads [file] and returns the unwrapped payload of type [T].
     *
     * Returns `null` when the file is missing (a clean "no state yet" — the caller's null handling
     * is correct there). When the file exists but cannot be read or parsed, the corrupt file is
     * logged (T1) and **quarantined** to a sibling `.corrupt` file so the data is recoverable and
     * is not silently hidden (P2); `null` is still returned so a single bad document cannot crash
     * startup. The next successful write replaces the document at its original path.
     */
    inline fun <reified T> readAtomic(
        file: File,
        gson: Gson,
    ): T? {
        if (!file.exists()) return null
        val text =
            try {
                val atomic = AtomicFile(file)
                String(atomic.readFully(), Charsets.UTF_8)
            } catch (error: IOException) {
                quarantineCorrupt(file, error, reason = "read")
                return null
            }
        // New envelope shape carries a "payload" key; pre-migration files are bare payloads.
        val (payloadJson, envelopeError) =
            if (text.contains("\"payload\"")) {
                val envelopeResult = runCatching { gson.fromJson<Envelope>(text, object : TypeToken<Envelope>() {}.type) }
                (envelopeResult.getOrNull()?.payload?.let { gson.toJson(it) } ?: text) to envelopeResult.exceptionOrNull()
            } else {
                text to null
            }
        val payloadResult = runCatching { gson.fromJson<T>(payloadJson, object : TypeToken<T>() {}.type) }
        if (payloadResult.isFailure) {
            // Prefer the envelope parse error as the root cause when it's the actual failure.
            quarantineCorrupt(file, envelopeError ?: payloadResult.exceptionOrNull()!!, reason = "parse")
        }
        return payloadResult.getOrNull()
    }

    /** Log (T1) + rename [file] to `file.corrupt` (P2) so a corrupt document is recoverable, not hidden. */
    @PublishedApi
    internal fun quarantineCorrupt(
        file: File,
        error: Throwable,
        reason: String,
    ) {
        Timber.e(error, "DurableJson %s failed for %s; quarantining to .corrupt", reason, file.name)
        runCatching {
            if (!file.exists()) return@runCatching
            val quarantine = File(file.parentFile, "${file.name}.corrupt")
            // If a prior quarantined copy exists (repeated read failures), don't overwrite the
            // first-known-bad copy; instead append a counter so every corrupt revision is kept.
            val target =
                if (!quarantine.exists()) {
                    quarantine
                } else {
                    var i = 1
                    while (File(file.parentFile, "${file.name}.corrupt.$i").exists()) i += 1
                    File(file.parentFile, "${file.name}.corrupt.$i")
                }
            file.renameTo(target)
        }.onFailure { Timber.w(it, "Could not quarantine corrupt file %s", file.name) }
    }
}
