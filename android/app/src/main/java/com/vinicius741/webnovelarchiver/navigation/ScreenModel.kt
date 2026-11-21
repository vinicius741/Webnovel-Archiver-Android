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

    /** State saved before a process death, waiting for the first model of its key ([restore]). */
    private val restoredStates = mutableMapOf<String, Any>()

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

    /** The model already kept for [key], without creating one (null when absent or of another type). */
    fun <T : ScreenModel> find(
        key: String,
        type: Class<T>,
    ): T? = models[key]?.takeIf(type::isInstance)?.let(type::cast)

    inline fun <reified T : ScreenModel> find(key: String): T? = find(key, T::class.java)

    /** Drops every model (and restored state) whose key is no longer in the history. */
    fun retainOnly(keys: Set<String>) {
        val dropped = models.keys - keys
        dropped.forEach { key -> models.remove(key)?.clear() }
        restoredStates.keys.retainAll(keys)
    }

    fun clear() {
        models.values.forEach(ScreenModel::clear)
        models.clear()
        restoredStates.clear()
    }

    /**
     * Keeps [state], read back from the activity's saved state after a process death, until [key]'s
     * model is created: its `create` takes it with [takeRestored].
     */
    fun restore(
        key: String,
        state: Any,
    ) {
        restoredStates[key] = state
    }

    /** The restored state of [key] not taken by a model yet, without taking it (to save it again). */
    fun <T : Any> peekRestored(
        key: String,
        type: Class<T>,
    ): T? = restoredStates[key]?.takeIf(type::isInstance)?.let(type::cast)

    /** Takes the restored state of [key] (null when none), so only the first model starts from it. */
    fun <T : Any> takeRestored(
        key: String,
        type: Class<T>,
    ): T? = peekRestored(key, type).also { restoredStates.remove(key) }

    inline fun <reified T : Any> takeRestored(key: String): T? = takeRestored(key, T::class.java)

    inline fun <reified T : Any> peekRestored(key: String): T? = peekRestored(key, T::class.java)

    internal val size: Int get() = models.size
}
