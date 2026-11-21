package com.vinicius741.webnovelarchiver.navigation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * The state holder of one screen (see android/AGENTS.md, "Screen architecture"). A subclass is
 * plain Kotlin: constructor dependencies, a `StateFlow` of immutable UI state, and action methods.
 * It never touches `View` or `Context`, so it can be tested on the JVM with fakes.
 *
 * Work the screen starts runs in [scope], which lives exactly as long as the model: from the first
 * render of its route until the route leaves the history (see [ScreenModelStore]). Views come and
 * go around it (fold, tab switch, Back to it), and each render binds to the same model.
 */
abstract class ScreenModel(
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    /** Called once when the model is discarded; release anything [scope] does not cover. */
    protected open fun onCleared() = Unit

    internal fun clear() {
        scope.cancel()
        onCleared()
    }
}

/**
 * Keeps one [ScreenModel] per history entry, keyed like [RouteViewMemory] by [AppRoute.stableKey]
 * (story-page tabs share their story's key, so the tabs share one model). Models are dropped, and
 * their scopes cancelled, when their key leaves the history ([retainOnly]) or the activity ends
 * ([clear]).
 */
class ScreenModelStore {
    private val models = mutableMapOf<String, ScreenModel>()

    /** The model for [key], created by [create] on first use. A model of another type is replaced. */
    @Suppress("UNCHECKED_CAST")
    fun <T : ScreenModel> get(
        key: String,
        type: Class<T>,
        create: () -> T,
    ): T {
        val existing = models[key]
        if (existing != null && type.isInstance(existing)) return existing as T
        existing?.clear()
        return create().also { models[key] = it }
    }

    inline fun <reified T : ScreenModel> get(
        key: String,
        noinline create: () -> T,
    ): T = get(key, T::class.java, create)

    /** Drops every model whose key is no longer in the history. */
    fun retainOnly(keys: Set<String>) {
        val dropped = models.keys - keys
        dropped.forEach { key -> models.remove(key)?.clear() }
    }

    fun clear() {
        models.values.forEach(ScreenModel::clear)
        models.clear()
    }

    internal val size: Int get() = models.size
}
