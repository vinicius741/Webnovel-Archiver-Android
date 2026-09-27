package com.vinicius741.webnovelarchiver.feature.browser

internal enum class CloudflareSolvePageState {
    VERIFIED,
    CHALLENGE_ACTIVE,
    DIFFERENT_PAGE,
    PAGE_UNAVAILABLE,
}

/**
 * Pure decisions for the visible Cloudflare verification flow.
 *
 * Clearance can be present on a challenged page or absent on an accepted page. The loaded source
 * content, rather than the cookie, decides whether an automatic retry is justified.
 */
internal object CloudflareSolvePlanning {
    fun pageState(
        isChallenge: Boolean,
        isSettled: Boolean,
        isRequestedResource: Boolean,
        isExpectedPage: Boolean,
    ): CloudflareSolvePageState =
        when {
            isChallenge -> CloudflareSolvePageState.CHALLENGE_ACTIVE
            !isSettled -> CloudflareSolvePageState.PAGE_UNAVAILABLE
            !isRequestedResource || !isExpectedPage -> CloudflareSolvePageState.DIFFERENT_PAGE
            else -> CloudflareSolvePageState.VERIFIED
        }
}
