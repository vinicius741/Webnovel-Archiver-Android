package com.vinicius741.webnovelarchiver.app

import com.vinicius741.webnovelarchiver.app.StartupPlanning.InitialRoute
import com.vinicius741.webnovelarchiver.navigation.AppRoute
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

    private val tap = AppRoute.Queue
    private val resume = AppRoute.Reader("story", "chapter")

    private fun route(
        dev: AppRoute? = null,
        browserUrl: String? = null,
        notification: AppRoute? = null,
        restored: Boolean = false,
        lateIntent: Boolean = false,
        resumeTarget: AppRoute? = null,
    ) = StartupPlanning.initialRoute(dev, browserUrl, notification, restored, lateIntent, resumeTarget)

    @Test
    fun devTargetAndBrowserImportWinOverEverythingElse() {
        assertEquals(
            InitialRoute.Dev(AppRoute.Settings),
            route(dev = AppRoute.Settings, browserUrl = "u", notification = tap, restored = true),
        )
        assertEquals(InitialRoute.BrowserImport("u"), route(browserUrl = "u", notification = tap, resumeTarget = resume))
    }

    @Test
    fun launchIntentTapIsAlreadyInsideARestoredStack() {
        assertEquals(InitialRoute.Restored, route(notification = tap, restored = true, lateIntent = false))
    }

    @Test
    fun tapDeliveredDuringStartupOpensOnTopOfTheRestoredStack() {
        assertEquals(InitialRoute.OnRestoredStack(tap), route(notification = tap, restored = true, lateIntent = true))
    }

    @Test
    fun coldStartTapAndTtsResumeOpenWithTheirParents() {
        assertEquals(InitialRoute.Deep(tap), route(notification = tap, resumeTarget = resume))
        assertEquals(InitialRoute.Deep(resume), route(resumeTarget = resume))
    }

    @Test
    fun restoredStackBeatsTtsResumeAndLibraryIsTheFallback() {
        assertEquals(InitialRoute.Restored, route(restored = true, resumeTarget = resume))
        assertEquals(InitialRoute.Library, route())
    }
}
