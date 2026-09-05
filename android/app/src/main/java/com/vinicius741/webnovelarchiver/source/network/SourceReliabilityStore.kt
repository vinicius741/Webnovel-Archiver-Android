package com.vinicius741.webnovelarchiver.source.network

import com.google.gson.Gson
import com.vinicius741.webnovelarchiver.data.storage.AtomicFileWrites
import timber.log.Timber
import java.io.File

/** The slice of [SourceReliabilityCoordinator] state that survives process death. */
data class PersistedHostReliability(
    val key: String,
    val canonicalHost: String,
    val manualVerificationRequired: Boolean,
    val cooldownUntil: Long,
    val browserTransportUntil: Long,
    val adaptiveMinimumGapMillis: Long,
    val requestCount: Long,
    val challengeCount: Long,
    val rateLimitCount: Long,
    val browserRenderCount: Long,
)

/**
 * Reads and writes the per-host reliability state as one small JSON document
 * (`source_reliability.json`) inside the app's storage root. Restoring an open manual circuit or
 * a live sticky Chromium-transport window means a process restart (e.g. an overnight OS kill of
 * the download service) resumes with the same access state instead of re-probing the source with
 * the already-rejected OkHttp fingerprint.
 */
class SourceReliabilityStore(
    directory: File,
) {
    private val file = File(directory, "source_reliability.json")
    private val gson = Gson()

    fun load(): List<PersistedHostReliability> {
        val raw = runCatching { file.readText() }.getOrNull() ?: return emptyList()
        return runCatching {
            gson.fromJson(raw, PersistedReliabilityDocument::class.java).hosts ?: emptyList()
        }.getOrDefault(emptyList())
    }

    /**
     * Returns false when the persistence attempt failed (R29): a circuit state that will not
     * survive restart deserves a diagnostic signal instead of silence. The store stays advisory —
     * callers log; they never crash on it.
     */
    fun save(states: List<PersistedHostReliability>): Boolean =
        runCatching {
            AtomicFileWrites.writeText(file, gson.toJson(PersistedReliabilityDocument(states)))
        }.onFailure { error ->
            Timber.e(error, "Failed to persist source reliability state; circuit state will not survive restart")
        }.isSuccess

    private data class PersistedReliabilityDocument(
        val hosts: List<PersistedHostReliability>? = null,
        val format: String = "wna-source-reliability",
        val version: Int = 1,
    )
}
