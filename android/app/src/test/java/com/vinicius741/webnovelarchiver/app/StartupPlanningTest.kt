package com.vinicius741.webnovelarchiver.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupPlanningTest {
    @Test
    fun splashHoldReleasesImmediatelyOnceUiIsReady() {
        assertTrue(StartupPlanning.shouldReleaseSplashHold(elapsedMs = 0, uiReady = true))
    }

    @Test
    fun splashHoldBlocksUntilGraceElapses() {
        assertFalse(StartupPlanning.shouldReleaseSplashHold(elapsedMs = StartupPlanning.SPLASH_HOLD_GRACE_MS - 1, uiReady = false))
    }

    @Test
    fun splashHoldReleasesAtGraceBoundary() {
        assertTrue(StartupPlanning.shouldReleaseSplashHold(elapsedMs = StartupPlanning.SPLASH_HOLD_GRACE_MS, uiReady = false))
    }

    @Test
    fun splashHoldHonorsCustomGrace() {
        assertFalse(StartupPlanning.shouldReleaseSplashHold(elapsedMs = 500, uiReady = false, graceMs = 1_000))
        assertTrue(StartupPlanning.shouldReleaseSplashHold(elapsedMs = 1_500, uiReady = false, graceMs = 1_000))
    }

    @Test
    fun skeletonCountUsesThreeRowsOnSingleColumn() {
        assertEquals(3, StartupPlanning.skeletonCardCount(1))
    }

    @Test
    fun skeletonCountUsesTwoRowsOnMultiColumn() {
        assertEquals(4, StartupPlanning.skeletonCardCount(2))
        assertEquals(6, StartupPlanning.skeletonCardCount(3))
    }

    @Test
    fun skeletonCountClampsOutOfRangeColumns() {
        assertEquals(3, StartupPlanning.skeletonCardCount(0))
        assertEquals(3, StartupPlanning.skeletonCardCount(-2))
        assertEquals(6, StartupPlanning.skeletonCardCount(7))
    }
}
