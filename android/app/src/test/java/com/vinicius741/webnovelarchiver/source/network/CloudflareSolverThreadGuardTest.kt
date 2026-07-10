package com.vinicius741.webnovelarchiver.source.network

import org.junit.Assert.assertThrows
import org.junit.Test

class CloudflareSolverThreadGuardTest {
    @Test
    fun mainThreadInvocationFailsBeforeWaiting() {
        assertThrows(IllegalStateException::class.java) {
            CloudflareSolverThreadGuard.requireBackgroundThread(isMainThread = true)
        }
    }

    @Test
    fun backgroundInvocationIsAllowed() {
        CloudflareSolverThreadGuard.requireBackgroundThread(isMainThread = false)
    }
}
