package com.vinicius741.webnovelarchiver.feature.library

import com.vinicius741.webnovelarchiver.domain.model.Tab
import com.vinicius741.webnovelarchiver.navigation.ScreenModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class AddStoryUiState(
    /** Tabs in display order. */
    val tabs: List<Tab>,
    /** The tab the story is saved to; null is Unassigned. */
    val tabId: String?,
    /** The URL draft. */
    val url: String,
    /** The running fetch's latest status, or null when no fetch runs. */
    val status: String?,
) {
    val fetching: Boolean get() = status != null
}

/**
 * The Add Story form (`AppRoute.AddStory`): the URL draft, the "Save to tab" pick, and the status of
 * the fetch it started. It lives while Add Story is in the history, so a fold, a source browser
 * opened from it, or Back to it keeps the draft, and opening it anew starts empty.
 */
internal class AddStoryScreenModel(
    private val tabs: () -> List<Tab>,
    /** The Library's saved tab, which new stories go to by default. */
    private val libraryTabId: () -> String?,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : ScreenModel(scope) {
    private var url = ""
    private var tabId: String? = null
    private var tabChosen = false
    private var status: String? = null
    private val mutableState = MutableStateFlow(compute())

    val state: StateFlow<AddStoryUiState> = mutableState.asStateFlow()

    /** Recomputes the state (the tabs may have changed) and returns it. */
    fun refresh(): AddStoryUiState = compute().also { mutableState.value = it }

    fun setUrl(text: String) {
        url = text
        refresh()
    }

    fun chooseTab(id: String?) {
        tabId = id
        tabChosen = true
        refresh()
    }

    /** The fetch reported progress; a non-null status means it runs. */
    fun fetchStatus(message: String) {
        status = message
        refresh()
    }

    fun fetchFailed() {
        status = null
        refresh()
    }

    private fun compute(): AddStoryUiState {
        val tabs = tabs().sortedBy { it.order }
        // Until a pick, new stories go to the Library's current tab when it is a real tab, else the first tab.
        if (!tabChosen) {
            val current = libraryTabId()
            tabId = tabs.firstOrNull { it.id == current }?.id ?: tabs.firstOrNull()?.id
        }
        return AddStoryUiState(tabs, tabId, url, status)
    }
}
