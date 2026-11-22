package com.vinicius741.webnovelarchiver.ui

import android.view.View
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Binds one render to a screen model's state: collects [flow] in [scope] and stops for good when
 * this view leaves the window (tab switch, fold, Back), so a replaced render is never updated.
 *
 * [dispatcher] defaults to `Main` rather than `Main.immediate`, so a state change made while a new
 * render is being built is applied after that build finishes.
 */
internal fun <T> View.bindWhileAttached(
    scope: CoroutineScope,
    flow: Flow<T>,
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
    onEach: suspend (T) -> Unit,
): Job {
    val job = scope.launch(dispatcher) { flow.collect { onEach(it) } }
    addOnAttachStateChangeListener(
        object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit

            override fun onViewDetachedFromWindow(v: View) {
                job.cancel()
                v.removeOnAttachStateChangeListener(this)
            }
        },
    )
    return job
}
