package com.vinicius741.webnovelarchiver.navigation

/**
 * Single owner for the app's route stack. Android rendering is intentionally kept outside it.
 *
 * Back always pops to the screen the user came from, and the stack is kept free of duplicates so
 * that is never surprising: opening a screen already in the history returns to it (dropping what
 * was above), and the transient [AppRoute.Working] screen is replaced by whatever follows it.
 */
class AppNavigator(
    initialRoute: AppRoute = AppRoute.Library,
) {
    private val routes = mutableListOf(initialRoute)

    val current: AppRoute
        get() = routes.last()

    val canGoBack: Boolean
        get() = routes.size > 1 || current.parent != null

    /** Stable keys of every route in the history; view memory for any other key is stale. */
    val stableKeys: Set<String>
        get() = routes.mapTo(mutableSetOf()) { it.stableKey }

    fun navigate(route: AppRoute) {
        if (route == current) return
        val existing = routes.indexOfLast { it.stableKey == route.stableKey }
        when {
            existing >= 0 -> {
                while (routes.lastIndex > existing) routes.removeAt(routes.lastIndex)
                routes[existing] = route
            }
            current == AppRoute.Working || route.name == current.name -> routes[routes.lastIndex] = route
            else -> routes += route
        }
    }

    /** Swaps the current screen for [route], e.g. a finished form for the item it created. */
    fun replace(route: AppRoute) {
        routes.removeAt(routes.lastIndex)
        if (routes.isEmpty()) routes += route else navigate(route)
    }

    fun back(): AppRoute? {
        if (routes.size > 1) {
            routes.removeAt(routes.lastIndex)
        } else {
            routes[0] = current.parent ?: return null
        }
        return current
    }

    fun encodedStack(): List<String> = routes.map(AppRouteCodec::encode)

    fun reset(route: AppRoute = AppRoute.Library) {
        routes.clear()
        routes += route
    }

    /** Opens [route] as if reached through its parents, for entry points that bypass the app's screens. */
    fun resetTo(route: AppRoute) {
        routes.clear()
        generateSequence(route) { it.parent }.toList().asReversed().forEach(routes::add)
    }

    fun restore(encodedRoutes: List<String>): Boolean {
        val decoded =
            encodedRoutes
                .takeIf { it.isNotEmpty() }
                ?.map(AppRouteCodec::decode)
                ?.takeIf { routes -> routes.all { it != null } }
                ?.filterNotNull()
                ?: return false
        // Replayed through navigate() so stacks saved by older builds lose their duplicate entries.
        reset(decoded.first())
        decoded.drop(1).forEach(::navigate)
        return true
    }
}
