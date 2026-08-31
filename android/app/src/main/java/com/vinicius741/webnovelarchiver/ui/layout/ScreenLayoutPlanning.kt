package com.vinicius741.webnovelarchiver.ui.layout

import com.vinicius741.webnovelarchiver.domain.model.DisplayPreferences

/**
 * Single source of truth for window size class and derived layout values. Pure on purpose (no
 * Android types, no I/O) so breakpoints are unit-testable. `screenLayoutMode` is the persisted
 * user override ("auto" | "cover" | "inner").
 */
object ScreenLayoutPlanning {
    const val SCREEN_LAYOUT_MODE_AUTO = "auto"
    const val SCREEN_LAYOUT_MODE_COVER = "cover"
    const val SCREEN_LAYOUT_MODE_INNER = "inner"

    val screenLayoutModes = setOf(SCREEN_LAYOUT_MODE_AUTO, SCREEN_LAYOUT_MODE_COVER, SCREEN_LAYOUT_MODE_INNER)

    // Width breakpoints (dp), matching Material 3 window-size classes.
    const val WIDTH_EXPANDED = 840
    const val WIDTH_MEDIUM = 600

    // Height breakpoints (dp).
    const val HEIGHT_EXPANDED = 900
    const val HEIGHT_MEDIUM = 480

    // A square-ish window (shortest side >= 460dp, aspect <= 1.8) is promoted to medium so a Fold's
    // inner screen just under 600dp still gets the tablet layout.
    const val FOLD_INNER_SHORTEST_SIDE = 460
    const val FOLD_INNER_MAX_ASPECT = 1.8

    /** Maximum content widths (dp) the library centers itself within, by column count. */
    const val LIBRARY_MAX_WIDTH_1_COL = 760
    const val LIBRARY_MAX_WIDTH_2_COL = 1040
    const val LIBRARY_MAX_WIDTH_3_COL = 1320

    /** Maximum reading column width (dp) so text lines stay readable on wide/inner screens. */
    const val READER_COLUMN_MAX_WIDTH = 800

    /** Queue content cap (dp) so the download manager doesn't stretch edge-to-edge on tablets. */
    const val QUEUE_MAX_WIDTH_EXPANDED = 1080
    const val QUEUE_MAX_WIDTH_OTHER = 920

    /** Settings content cap (dp) by width class. */
    const val SETTINGS_MAX_WIDTH_EXPANDED = 840
    const val SETTINGS_MAX_WIDTH_MEDIUM = 720
}

enum class WidthClass { COMPACT, MEDIUM, EXPANDED }

enum class HeightClass { COMPACT, MEDIUM, EXPANDED }

/** How the user wants the app to treat the screen, independent of physical fold detection. */
enum class ScreenLayoutMode {
    AUTO,
    COVER,
    INNER,
    ;

    companion object {
        /** Normalizes an arbitrary stored string to a valid mode, defaulting to AUTO. */
        fun fromStored(value: String?): ScreenLayoutMode =
            when (value) {
                ScreenLayoutPlanning.SCREEN_LAYOUT_MODE_COVER -> COVER
                ScreenLayoutPlanning.SCREEN_LAYOUT_MODE_INNER -> INNER
                else -> AUTO
            }
    }
}

/** Inputs to layout resolution; all sizes in dp. hasFoldingFeature comes from androidx.window. */
data class ScreenLayout(
    val widthDp: Int,
    val heightDp: Int,
    val hasFoldingFeature: Boolean,
    val mode: ScreenLayoutMode = ScreenLayoutMode.AUTO,
)

/** Derived, screen-consumable layout values. */
data class ScreenLayoutResult(
    val widthClass: WidthClass,
    val heightClass: HeightClass,
    val numColumns: Int,
    val isLargeScreen: Boolean,
    val isTwoPane: Boolean,
    val isCompactHeight: Boolean,
    val hasFoldingFeature: Boolean,
    val mode: ScreenLayoutMode,
)

fun resolveScreenLayout(layout: ScreenLayout): ScreenLayoutResult {
    val width = layout.widthDp
    val height = layout.heightDp
    val shortestSide = minOf(width, height)
    val longestSide = maxOf(width, height)
    val aspectRatio = if (shortestSide > 0) longestSide.toDouble() / shortestSide.toDouble() else Double.POSITIVE_INFINITY

    val automaticWidthClass = automaticWidthClass(width, shortestSide, aspectRatio)

    val widthClass: WidthClass =
        when (layout.mode) {
            ScreenLayoutMode.COVER -> WidthClass.COMPACT
            ScreenLayoutMode.INNER -> if (width >= ScreenLayoutPlanning.WIDTH_EXPANDED) WidthClass.EXPANDED else WidthClass.MEDIUM
            ScreenLayoutMode.AUTO ->
                when {
                    layout.hasFoldingFeature -> if (width >= ScreenLayoutPlanning.WIDTH_EXPANDED) WidthClass.EXPANDED else WidthClass.MEDIUM
                    else -> automaticWidthClass
                }
        }

    val heightClass = heightClass(height)
    val isCompactHeight = heightClass == HeightClass.COMPACT

    val numColumns =
        when (widthClass) {
            WidthClass.EXPANDED -> 3
            WidthClass.MEDIUM -> if (isCompactHeight) 1 else 2
            WidthClass.COMPACT -> 1
        }
    val isLargeScreen = numColumns > 1
    val isTwoPane = widthClass != WidthClass.COMPACT && !isCompactHeight

    return ScreenLayoutResult(
        widthClass = widthClass,
        heightClass = heightClass,
        numColumns = numColumns,
        isLargeScreen = isLargeScreen,
        isTwoPane = isTwoPane,
        isCompactHeight = isCompactHeight,
        hasFoldingFeature = layout.hasFoldingFeature,
        mode = layout.mode,
    )
}

private fun automaticWidthClass(
    width: Int,
    shortestSide: Int,
    aspectRatio: Double,
): WidthClass {
    if (width >= ScreenLayoutPlanning.WIDTH_EXPANDED) return WidthClass.EXPANDED
    if (width >= ScreenLayoutPlanning.WIDTH_MEDIUM) return WidthClass.MEDIUM
    if (shortestSide >= ScreenLayoutPlanning.FOLD_INNER_SHORTEST_SIDE && aspectRatio <= ScreenLayoutPlanning.FOLD_INNER_MAX_ASPECT) {
        return WidthClass.MEDIUM
    }
    return WidthClass.COMPACT
}

private fun heightClass(height: Int): HeightClass {
    if (height >= ScreenLayoutPlanning.HEIGHT_EXPANDED) return HeightClass.EXPANDED
    if (height >= ScreenLayoutPlanning.HEIGHT_MEDIUM) return HeightClass.MEDIUM
    return HeightClass.COMPACT
}

/** Maximum library content width (dp) for a given column count. */
fun libraryMaxContentWidth(numColumns: Int): Int =
    when (numColumns) {
        1 -> ScreenLayoutPlanning.LIBRARY_MAX_WIDTH_1_COL
        2 -> ScreenLayoutPlanning.LIBRARY_MAX_WIDTH_2_COL
        else -> ScreenLayoutPlanning.LIBRARY_MAX_WIDTH_3_COL
    }

/** Reader horizontal padding (dp) by width class. */
fun readerSidePadding(widthClass: WidthClass): Int =
    when (widthClass) {
        WidthClass.EXPANDED -> 32
        WidthClass.MEDIUM -> 20
        WidthClass.COMPACT -> 8
    }

/** Queue content cap (dp) by width class. */
fun queueMaxWidth(widthClass: WidthClass): Int =
    when (widthClass) {
        WidthClass.EXPANDED -> ScreenLayoutPlanning.QUEUE_MAX_WIDTH_EXPANDED
        else -> ScreenLayoutPlanning.QUEUE_MAX_WIDTH_OTHER
    }

/** Settings content cap (dp) by width class. */
fun settingsMaxWidth(widthClass: WidthClass): Int =
    when (widthClass) {
        WidthClass.EXPANDED -> ScreenLayoutPlanning.SETTINGS_MAX_WIDTH_EXPANDED
        WidthClass.MEDIUM -> ScreenLayoutPlanning.SETTINGS_MAX_WIDTH_MEDIUM
        WidthClass.COMPACT -> ScreenLayoutPlanning.SETTINGS_MAX_WIDTH_MEDIUM
    }
