package com.vinicius741.webnovelarchiver.app

/**
 * Pure decisions for the cold-start experience (QA 2026-08-27 F1a). The platform splash screen is
 * held over the window until the first real content frame; when repository hydration is slow, it
 * releases into a branded startup state after a grace period instead of holding a static splash
 * for the whole hydration.
 */
object StartupPlanning {
    /** How long the platform splash may cover the window before falling through to the branded
     *  startup state. Fast hydrations finish first and never show the intermediate state. */
    const val SPLASH_HOLD_GRACE_MS = 600L

    /** Releases the splash hold once the UI is ready (fast cold start) or the grace period has
     *  elapsed (slow hydration → branded startup state). */
    fun shouldReleaseSplashHold(
        elapsedMs: Long,
        uiReady: Boolean,
        graceMs: Long = SPLASH_HOLD_GRACE_MS,
    ): Boolean = uiReady || elapsedMs >= graceMs

    /** How many skeleton story cards the startup state lays out: enough rows to suggest the grid's
     *  shape without overflowing small windows. */
    fun skeletonCardCount(numColumns: Int): Int {
        val columns = numColumns.coerceIn(1, 3)
        val rows = if (columns >= 2) 2 else 3
        return columns * rows
    }
}
