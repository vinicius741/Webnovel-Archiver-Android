package com.vinicius741.webnovelarchiver.navigation

/** Single owner for the app's route stack. Android rendering is intentionally kept outside it. */
class AppNavigator(
    initialRoute: AppRoute = AppRoute.Library,
) {
    private val routes = mutableListOf(initialRoute)

    val current: AppRoute
        get() = routes.last()

    val canGoBack: Boolean
        get() = routes.size > 1

    fun navigate(route: AppRoute) {
        if (route == current) return
        if (route.name == current.name) {
            routes[routes.lastIndex] = route
        } else {
            routes += route
        }
    }

    fun back(): AppRoute? {
        if (!canGoBack) return null
        routes.removeAt(routes.lastIndex)
        return current
    }

    fun encodedStack(): List<String> = routes.map(AppRouteCodec::encode)

    fun reset(route: AppRoute = AppRoute.Library) {
        routes.clear()
        routes += route
    }

    fun restore(encodedRoutes: List<String>): Boolean {
        val decoded = encodedRoutes.map(AppRouteCodec::decode)
        if (decoded.isEmpty() || decoded.any { it == null }) return false
        routes.clear()
        routes += decoded.filterNotNull()
        return true
    }
}
