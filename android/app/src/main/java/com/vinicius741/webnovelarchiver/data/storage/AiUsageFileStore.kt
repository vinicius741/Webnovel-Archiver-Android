package com.vinicius741.webnovelarchiver.data.storage

import com.google.gson.Gson
import com.vinicius741.webnovelarchiver.ai.AiUsagePlanning
import com.vinicius741.webnovelarchiver.domain.model.AiUsageLedger
import java.io.File

/** Small device-local document store kept outside the already-large [AppStorage] facade. */
internal class AiUsageFileStore(
    root: File,
    private val gson: Gson,
    private val appVersion: String,
) {
    private val file = File(root, "ai_usage.json")

    @Synchronized
    fun load(): AiUsageLoadResult =
        when (val result = DurableJson.readAtomicResult<AiUsageLedger>(file, gson)) {
            is DurableReadResult.Present -> AiUsageLoadResult.Ready(AiUsagePlanning.normalizeLedger(result.value))
            DurableReadResult.Absent -> AiUsageLoadResult.Ready(AiUsageLedger())
            is DurableReadResult.Corrupt ->
                if (result.quarantinedFile != null || !file.exists()) {
                    AiUsageLoadResult.Ready(AiUsageLedger())
                } else {
                    AiUsageLoadResult.Blocked("Could not quarantine corrupt AI usage history", result.cause)
                }
            is DurableReadResult.UnsupportedSchema ->
                AiUsageLoadResult.Blocked(
                    "AI usage history uses schema ${result.foundVersion}; this app supports ${result.supportedVersion}",
                )
            is DurableReadResult.IoFailure -> AiUsageLoadResult.Blocked("Could not read AI usage history", result.cause)
        }

    fun readOrThrow(): AiUsageLedger =
        when (val result = load()) {
            is AiUsageLoadResult.Ready -> result.ledger
            is AiUsageLoadResult.Blocked -> throw IllegalStateException(result.message, result.cause)
        }

    @Synchronized
    fun write(ledger: AiUsageLedger) {
        val normalized = AiUsagePlanning.normalizeLedger(ledger)
        DurableJson.writeAtomic(file, gson, DurableJson.envelope(normalized, appVersion))
    }
}

internal sealed interface AiUsageLoadResult {
    data class Ready(
        val ledger: AiUsageLedger,
    ) : AiUsageLoadResult

    data class Blocked(
        val message: String,
        val cause: Throwable? = null,
    ) : AiUsageLoadResult
}
