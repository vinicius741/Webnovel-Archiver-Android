package com.vinicius741.webnovelarchiver.navigation

/**
 * Switches to a top-level destination the way a navigation bar / rail tap does: its history starts
 * over the Library root. Use it for in-screen shortcuts to another destination (e.g. Downloads'
 * "Find new chapters" → Updates) so they don't stack one destination on top of another.
 */
internal fun ScreenHost.openDestination(destination: ShellDestination) {
    val history = ShellNavigationPlanning.historyFor(destination)
    if (navigator.history == history) return
    navigator.reset(history.first())
    history.drop(1).forEach(navigator::navigate)
    renderRoute(navigator.current)
}
