package com.vinicius741.webnovelarchiver.app

import com.vinicius741.webnovelarchiver.navigation.AppRoute

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

    /** Where the first rendered screen comes from once startup finishes; see [initialRoute]. */
    sealed interface InitialRoute {
        /** A debug dev-launch target, on top of its parents. */
        data class Dev(
            val route: AppRoute,
        ) : InitialRoute

        data class BrowserImport(
            val url: String,
        ) : InitialRoute

        /** A notification tap delivered after the stack was restored: on top, so Back returns. */
        data class OnRestoredStack(
            val route: AppRoute,
        ) : InitialRoute

        /** A cold-start target with its parents beneath it, so Back walks up to the Library. */
        data class Deep(
            val route: AppRoute,
        ) : InitialRoute

        data object Restored : InitialRoute

        data object Library : InitialRoute
    }

    /**
     * Precedence for the first screen. A restored stack already contains whatever the original
     * launch intent's notification tap opened, so that tap only counts again when it arrived as a
     * new intent while startup was still running.
     */
    fun initialRoute(
        devTarget: AppRoute?,
        browserImportUrl: String?,
        notificationRoute: AppRoute?,
        restoredNavigation: Boolean,
        intentArrivedBeforeUiReady: Boolean,
        readerResumeTarget: AppRoute?,
    ): InitialRoute {
        val freshNotification = notificationRoute?.takeUnless { restoredNavigation && !intentArrivedBeforeUiReady }
        return when {
            devTarget != null -> InitialRoute.Dev(devTarget)
            browserImportUrl != null -> InitialRoute.BrowserImport(browserImportUrl)
            freshNotification != null && restoredNavigation -> InitialRoute.OnRestoredStack(freshNotification)
            freshNotification != null -> InitialRoute.Deep(freshNotification)
            restoredNavigation -> InitialRoute.Restored
            readerResumeTarget != null -> InitialRoute.Deep(readerResumeTarget)
            else -> InitialRoute.Library
        }
    }
}
