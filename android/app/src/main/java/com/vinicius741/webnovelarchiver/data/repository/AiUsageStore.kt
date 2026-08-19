package com.vinicius741.webnovelarchiver.data.repository

import com.vinicius741.webnovelarchiver.ai.AiUsagePlanning
import com.vinicius741.webnovelarchiver.data.storage.AiUsageLoadResult
import com.vinicius741.webnovelarchiver.domain.model.AiUsageLedger
import com.vinicius741.webnovelarchiver.domain.model.AiUsageRecord

/** Mutable ledger cache. [AppRepository] supplies the transaction lock around writes and loads. */
internal class AiUsageStore {
    @Volatile
    private var state: State = State.Blocked(AiUsageLedger(), "AI usage history has not loaded yet")

    fun reload(load: () -> AiUsageLoadResult) {
        state =
            when (val result = load()) {
                is AiUsageLoadResult.Ready -> State.Ready(result.ledger)
                is AiUsageLoadResult.Blocked -> State.Blocked(state.ledger, result.message)
            }
    }

    fun snapshot(): AiUsageLedger = AiUsagePlanning.normalizeLedger(state.ledger)

    fun record(
        record: AiUsageRecord,
        write: (AiUsageLedger) -> Unit,
    ): AiUsageLedger {
        val current = state
        check(current is State.Ready) { (current as State.Blocked).message }
        val updated = AiUsagePlanning.recordAttempt(current.ledger, record)
        write(updated)
        state = State.Ready(updated)
        return snapshot()
    }

    private sealed interface State {
        val ledger: AiUsageLedger

        data class Ready(
            override val ledger: AiUsageLedger,
        ) : State

        data class Blocked(
            override val ledger: AiUsageLedger,
            val message: String,
        ) : State
    }
}

fun AppRepository.getAiUsageLedger(): AiUsageLedger = aiUsage.snapshot()

/** Records one API attempt and updates the in-memory cache after the atomic write succeeds. */
suspend fun AppRepository.recordAiUsage(record: AiUsageRecord): AiUsageLedger =
    storageTransaction { aiUsage.record(record, storage.aiUsage::write) }
