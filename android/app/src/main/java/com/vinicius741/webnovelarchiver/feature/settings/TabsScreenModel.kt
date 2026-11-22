package com.vinicius741.webnovelarchiver.feature.settings

import com.vinicius741.webnovelarchiver.domain.model.Tab
import com.vinicius741.webnovelarchiver.navigation.ScreenModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The library tabs Manage tabs edits, and how many novels each holds. */
internal interface TabsData {
    fun tabs(): List<Tab>

    /** Novels per tab id. */
    fun novelCounts(): Map<String, Int>

    suspend fun saveTabs(tabs: List<Tab>)

    /** Moves the novels of [tabId] to Unassigned. */
    suspend fun unassign(tabId: String)

    fun newTabId(): String

    fun now(): Long
}

internal data class TabsUiState(
    /** In display order. */
    val tabs: List<Tab>,
    val novelCounts: Map<String, Int>,
)

/**
 * Manage tabs (`AppRoute.Tabs`): the tabs in order with their novel counts, and the new tab name
 * being typed, which a fold keeps (it is read at build time, not part of [state]).
 */
internal class TabsScreenModel(
    private val data: TabsData,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : ScreenModel(scope) {
    var newTabName: String = ""
        private set
    private val mutableState = MutableStateFlow(compute())
    val state: StateFlow<TabsUiState> = mutableState.asStateFlow()

    fun refresh(): TabsUiState = compute().also { mutableState.value = it }

    fun setNewTabName(name: String) {
        newTabName = name
    }

    /** Adds a tab named [newTabName]; false (nothing added) when the name is blank or taken. */
    fun addTab(): Boolean {
        val tabs = state.value.tabs
        val next = TabPlanning.create(tabs, newTabName, data.newTabId(), data.now())
        if (next.size <= tabs.size) return false
        save {
            data.saveTabs(next)
            newTabName = ""
        }
        return true
    }

    fun move(
        from: Int,
        to: Int,
    ) = save { data.saveTabs(TabPlanning.move(state.value.tabs, from, to)) }

    fun rename(
        tabId: String,
        name: String,
    ) = save { data.saveTabs(TabPlanning.rename(state.value.tabs, tabId, name)) }

    /** Deletes the tab; its novels become Unassigned. */
    fun delete(tabId: String) =
        save {
            data.unassign(tabId)
            data.saveTabs(TabPlanning.delete(state.value.tabs, tabId))
        }

    private fun save(write: suspend () -> Unit) {
        scope.launch {
            withContext(NonCancellable) { write() }
            refresh()
        }
    }

    private fun compute(): TabsUiState = TabsUiState(TabPlanning.normalizeOrders(data.tabs()), data.novelCounts())
}
