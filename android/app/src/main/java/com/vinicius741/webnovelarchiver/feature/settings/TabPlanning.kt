package com.vinicius741.webnovelarchiver.feature.settings

import com.vinicius741.webnovelarchiver.domain.model.Tab
import com.vinicius741.webnovelarchiver.ui.size

object TabPlanning {
    fun normalizeOrders(tabs: List<Tab>): MutableList<Tab> =
        tabs
            .sortedBy { it.order }
            .mapIndexed { index, tab -> tab.copy(order = index) }
            .toMutableList()

    private fun assignOrders(tabs: List<Tab>): MutableList<Tab> = tabs.mapIndexed { index, tab -> tab.copy(order = index) }.toMutableList()

    fun create(
        tabs: List<Tab>,
        rawName: String,
        id: String,
        createdAt: Long,
    ): MutableList<Tab> {
        val normalized = normalizeOrders(tabs)
        val name = rawName.trim()
        if (name.isBlank()) return normalized
        normalized.add(Tab(id = id, name = name, order = normalized.size, createdAt = createdAt))
        return normalized
    }

    fun rename(
        tabs: List<Tab>,
        id: String,
        rawName: String,
    ): MutableList<Tab> {
        val name = rawName.trim()
        val normalized = normalizeOrders(tabs)
        if (name.isBlank()) return normalized
        return normalized.map { tab -> if (tab.id == id) tab.copy(name = name) else tab }.toMutableList()
    }

    fun delete(
        tabs: List<Tab>,
        id: String,
    ): MutableList<Tab> = normalizeOrders(tabs.filterNot { it.id == id })

    fun move(
        tabs: List<Tab>,
        fromIndex: Int,
        toIndex: Int,
    ): MutableList<Tab> {
        val normalized = normalizeOrders(tabs)
        if (fromIndex !in normalized.indices || toIndex !in normalized.indices || fromIndex == toIndex) {
            return normalized
        }
        val moved = normalized.removeAt(fromIndex)
        normalized.add(toIndex, moved)
        return assignOrders(normalized)
    }
}
