package com.vinicius741.webnovelarchiver.feature.library

import com.vinicius741.webnovelarchiver.ui.layout.libraryMaxContentWidth

/** The two Library layouts: detail-rich list cards, or a cover-first grid. */
internal enum class LibraryViewMode(
    val stored: String,
) {
    LIST("list"),
    COVERS("covers"),
    ;

    val toggled: LibraryViewMode get() = if (this == LIST) COVERS else LIST

    companion object {
        /** Unknown or missing values keep the original list layout. */
        fun fromStored(value: String?): LibraryViewMode = entries.firstOrNull { it.stored == value } ?: LIST
    }
}

/** Column count and centered width cap for one render of the Library grid. */
internal data class LibraryGridSpec(
    val viewMode: LibraryViewMode,
    val columns: Int,
    val maxContentWidthDp: Int,
)

internal object LibraryViewModePlanning {
    /** Narrowest cover tile (dp) before the grid drops a column; two always fit on a phone. */
    const val MIN_COVER_TILE_WIDTH = 150
    const val MIN_COVER_COLUMNS = 2

    /** Horizontal gap (dp) between tiles, matching the adapter's per-item side margins. */
    const val COVER_TILE_GAP = 16

    /** Screen side padding (dp) applied by `screen()` on both edges. */
    private const val SCREEN_SIDE_PADDING = 24

    /**
     * The list keeps the size-class columns. Covers fill the capped content width with as many
     * tiles as stay at least [MIN_COVER_TILE_WIDTH] wide, so wider windows show more covers
     * rather than ever-larger ones.
     */
    fun gridSpec(
        viewMode: LibraryViewMode,
        listColumns: Int,
        windowWidthDp: Int,
    ): LibraryGridSpec {
        val maxWidth = libraryMaxContentWidth(listColumns.coerceAtLeast(1))
        if (viewMode == LibraryViewMode.LIST) return LibraryGridSpec(viewMode, listColumns.coerceAtLeast(1), maxWidth)
        val coverMaxWidth = libraryMaxContentWidth(Int.MAX_VALUE)
        val contentWidth = (windowWidthDp - 2 * SCREEN_SIDE_PADDING).coerceIn(0, coverMaxWidth)
        val columns = (contentWidth / (MIN_COVER_TILE_WIDTH + COVER_TILE_GAP)).coerceAtLeast(MIN_COVER_COLUMNS)
        return LibraryGridSpec(viewMode, columns, coverMaxWidth)
    }
}
