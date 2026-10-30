package com.vinicius741.webnovelarchiver.navigation

/** In-screen state that system Back clears before it leaves the screen, e.g. an active search. */
class BackIntercept(
    val isActive: () -> Boolean,
    val clear: () -> Unit,
)

/**
 * What system Back does on the current screen, in priority order: a modal [close] (Library
 * multi-select), then clearing an active [intercept], then [navigateUp]. When none applies the app
 * leaves Back to Android, which closes the app.
 */
class ScreenBack(
    val close: (() -> Unit)? = null,
    val intercept: BackIntercept? = null,
    val navigateUp: (() -> Unit)? = null,
) {
    val handlesBack: Boolean
        get() = close != null || intercept?.isActive() == true || navigateUp != null

    /** Runs the first applicable step; `false` means the press is Android's to handle. */
    fun handle(): Boolean {
        val step =
            close
                ?: intercept?.takeIf { it.isActive() }?.clear
                ?: navigateUp
                ?: return false
        step()
        return true
    }
}
