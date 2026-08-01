package com.vinicius741.webnovelarchiver.feature.browser

internal enum class CloudflareSolvePageState {
    VERIFIED,
    CHALLENGE_ACTIVE,
    READY_WITHOUT_CLEARANCE,
    PAGE_UNAVAILABLE,
}

/**
 * Pure decisions for the visible Cloudflare verification flow.
 *
 * A successfully rendered browser page does not guarantee that Cloudflare will mint a
 * `cf_clearance` cookie. The no-cookie state therefore remains user-overridable instead of becoming
 * a dead end.
 */
internal object CloudflareSolvePlanning {
    fun pageState(
        hasClearance: Boolean,
        isChallenge: Boolean,
        hasPageContent: Boolean,
    ): CloudflareSolvePageState =
        when {
            isChallenge -> CloudflareSolvePageState.CHALLENGE_ACTIVE
            !hasPageContent -> CloudflareSolvePageState.PAGE_UNAVAILABLE
            hasClearance -> CloudflareSolvePageState.VERIFIED
            else -> CloudflareSolvePageState.READY_WITHOUT_CLEARANCE
        }

    fun requiresConfirmation(hasClearance: Boolean): Boolean = !hasClearance
}
