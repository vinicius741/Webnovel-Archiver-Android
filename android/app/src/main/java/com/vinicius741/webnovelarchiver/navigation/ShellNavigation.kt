package com.vinicius741.webnovelarchiver.navigation

/**
 * Switches to a top-level destination the way a navigation bar / rail tap does: its history starts
 * over the Library root. Use it for in-screen shortcuts to another destination (e.g. Downloads'
 * "Find new chapters" → Updates) so they don't stack one destination on top of another. The new
 * screen slides in from the side of the destination it came from (see [com.vinicius741.webnovelarchiver.ui.AppShell.playDestinationEnter]).
 */
internal fun ScreenHost.openDestination(destination: ShellDestination) {
    val history = ShellNavigationPlanning.historyFor(destination)
    if (navigator.history == history) {
        shell.settleIndicators()
        return
    }
    val direction = ShellNavigationPlanning.transitionDirection(ShellNavigationPlanning.destinationOf(navigator.history), destination)
    navigator.reset(history.first())
    history.drop(1).forEach(navigator::navigate)
    renderRoute(navigator.current)
    shell.playDestinationEnter(frame.takeIf { it.childCount > 0 }?.getChildAt(0), direction)
}
