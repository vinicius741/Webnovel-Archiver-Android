package com.vinicius741.webnovelarchiver.ui

import androidx.recyclerview.widget.RecyclerView
import com.vinicius741.webnovelarchiver.navigation.RouteViewMemory
import com.vinicius741.webnovelarchiver.navigation.ScreenHost

/**
 * Saves the rendered screen's scroll offset and main-list position into [RouteViewMemory] under
 * the key the screen was rendered with, so Back (or a same-screen re-render) can put them back.
 */
internal fun ScreenHost.captureScreenViewState() {
    val key = frame.tag as? String ?: return
    findScrollView(frame)?.let { routeMemory.scrollPositions[key] = it.scrollY }
    routeMemory.trackedList
        ?.layoutManager
        ?.onSaveInstanceState()
        ?.let { routeMemory.listStates[key] = it }
}

/**
 * Makes [list] the current screen's remembered list: its position is captured when the screen is
 * replaced and restored here when the same history entry is rendered again. Call it after the
 * list's adapter is set, while the screen body is being built.
 */
internal fun ScreenHost.rememberListPosition(list: RecyclerView) {
    routeMemory.trackedList = list
    val saved = routeMemory.listStates[navigator.current.stableKey] ?: return
    list.post { list.layoutManager?.onRestoreInstanceState(saved) }
}
