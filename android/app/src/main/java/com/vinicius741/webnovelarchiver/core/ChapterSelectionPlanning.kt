package com.vinicius741.webnovelarchiver.core

/** Pure selection updates shared by tap-to-range and drag-to-paint chapter selection. */
object ChapterSelectionPlanning {
    fun applyRange(
        selectedIds: Set<String>,
        orderedIds: List<String>,
        startPosition: Int,
        endPosition: Int,
        selecting: Boolean,
    ): Set<String> {
        if (orderedIds.isEmpty()) return selectedIds

        val start = minOf(startPosition, endPosition).coerceIn(orderedIds.indices)
        val end = maxOf(startPosition, endPosition).coerceIn(orderedIds.indices)
        return selectedIds.toMutableSet().apply {
            for (position in start..end) {
                if (selecting) add(orderedIds[position]) else remove(orderedIds[position])
            }
        }
    }
}
