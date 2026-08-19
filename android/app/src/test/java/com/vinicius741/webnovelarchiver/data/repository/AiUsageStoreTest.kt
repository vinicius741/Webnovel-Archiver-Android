package com.vinicius741.webnovelarchiver.data.repository

import com.vinicius741.webnovelarchiver.data.storage.AiUsageLoadResult
import com.vinicius741.webnovelarchiver.domain.model.AiUsageLedger
import com.vinicius741.webnovelarchiver.domain.model.AiUsageRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AiUsageStoreTest {
    @Test
    fun `reload after clear discards cached history before the next write`() {
        val store = AiUsageStore()
        store.reload { AiUsageLoadResult.Ready(ledgerWith("old", "0.10")) }
        store.reload { AiUsageLoadResult.Ready(AiUsageLedger()) }
        var persisted: AiUsageLedger? = null

        store.record(record("new", "0.02")) { persisted = it }

        assertEquals(listOf("new"), store.snapshot().recentRecords.map(AiUsageRecord::id))
        assertEquals("0.02", persisted?.allTimeCostUsd)
    }

    @Test
    fun `failed reload keeps the prior snapshot but blocks overwrite`() {
        val store = AiUsageStore()
        store.reload { AiUsageLoadResult.Ready(ledgerWith("old", "0.10")) }
        store.reload { AiUsageLoadResult.Blocked("read failed") }
        var writeCalled = false

        val error =
            assertThrows(IllegalStateException::class.java) {
                store.record(record("new", "0.02")) { writeCalled = true }
            }

        assertEquals("read failed", error.message)
        assertEquals(false, writeCalled)
        assertEquals(listOf("old"), store.snapshot().recentRecords.map(AiUsageRecord::id))
    }

    private fun ledgerWith(
        id: String,
        cost: String,
    ): AiUsageLedger =
        AiUsageLedger(
            allTimeCostUsd = cost,
            allTimeCallCount = 1,
            recentRecords = mutableListOf(record(id, cost)),
        )

    private fun record(
        id: String,
        cost: String,
    ): AiUsageRecord =
        AiUsageRecord(
            id = id,
            operationId = id,
            feature = "description",
            model = "test/model",
            costUsd = cost,
            outcome = "completed",
        )
}
