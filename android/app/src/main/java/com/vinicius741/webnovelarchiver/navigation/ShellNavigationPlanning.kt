package com.vinicius741.webnovelarchiver.navigation

/** The five top-level destinations shown in the bottom navigation bar and the inner-screen rail. */
enum class ShellDestination(
    val route: AppRoute,
) {
    LIBRARY(AppRoute.Library),
    UPDATES(AppRoute.Updates),
    LISTEN(AppRoute.Narration),
    DOWNLOADS(AppRoute.Queue),
    SETTINGS(AppRoute.Settings),
}

/** Which app chrome a route shows, and which destination it belongs to. */
object ShellNavigationPlanning {
    /**
     * The destination to highlight for the current [history] (oldest first): the newest destination
     * root still in the history (a novel opened from Updates stays under Updates), else the
     * destination the current route's parent chain reaches.
     */
    fun destinationOf(history: List<AppRoute>): ShellDestination? {
        history.asReversed().forEach { route -> rootDestination(route)?.let { return it } }
        val current = history.lastOrNull() ?: return null
        return generateSequence(current) { it.parent }.firstNotNullOfOrNull(::rootDestination)
    }

    /** Full-screen flows (reading, playback, adding, selecting) hide the navigation bar and rail. */
    fun showsNavigation(route: AppRoute): Boolean =
        when (route) {
            is AppRoute.Reader, is AppRoute.ChapterSelection, AppRoute.Player, AppRoute.AddStory, AppRoute.Working -> false
            else -> true
        }

    /**
     * The Reader docks its own player, Now Playing is the player, and Listen leads with a Now
     * playing card, so all three hide the mini player.
     */
    fun showsMiniPlayer(route: AppRoute): Boolean = route !is AppRoute.Reader && route != AppRoute.Player && route != AppRoute.Narration

    /** Destination roots are reached from the navigation, so their app bars show no back arrow. */
    fun isDestinationRoot(route: AppRoute): Boolean = ShellDestination.entries.any { it.route == route }

    /** The history a destination tap starts: the destination over the Library root. */
    fun historyFor(destination: ShellDestination): List<AppRoute> =
        if (destination == ShellDestination.LIBRARY) listOf(AppRoute.Library) else listOf(AppRoute.Library, destination.route)

    /**
     * Which way a destination switch moves along the navigation: 1 toward later destinations (right
     * on the bar, down on the rail), -1 toward earlier ones, 0 when it stays put or comes from no destination.
     */
    fun transitionDirection(
        from: ShellDestination?,
        to: ShellDestination,
    ): Int = if (from == null) 0 else to.ordinal.compareTo(from.ordinal).coerceIn(-1, 1)

    private fun rootDestination(route: AppRoute): ShellDestination? = ShellDestination.entries.firstOrNull { it.route == route }
}
