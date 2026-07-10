package com.vinicius741.webnovelarchiver.data.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class MaintenanceCoordinatorTest {
    @Test
    fun exclusiveMaintenancePublishesStateAndBlocksStorageAccess() {
        val coordinator = MaintenanceCoordinator()
        val owner = Any()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writerFinished = CountDownLatch(1)

        val maintenance =
            thread {
                coordinator.runExclusive(owner, MaintenanceOperation.RestoreFull) {
                    entered.countDown()
                    release.await(2, TimeUnit.SECONDS)
                }
            }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        assertEquals(MaintenanceState.Running(MaintenanceOperation.RestoreFull), coordinator.state.value)

        val writer =
            thread {
                coordinator.withStorageAccess(owner) { writerFinished.countDown() }
            }
        assertFalse(writerFinished.await(100, TimeUnit.MILLISECONDS))
        release.countDown()
        maintenance.join(2_000)
        writer.join(2_000)

        assertTrue(writerFinished.await(100, TimeUnit.MILLISECONDS))
        assertEquals(MaintenanceState.Idle, coordinator.state.value)
    }

    @Test
    fun stateReturnsToIdleWhenMaintenanceFails() {
        val coordinator = MaintenanceCoordinator()
        runCatching {
            coordinator.runExclusive(Any(), MaintenanceOperation.ImportJson) {
                error("validation failed")
            }
        }
        assertEquals(MaintenanceState.Idle, coordinator.state.value)
    }
}
