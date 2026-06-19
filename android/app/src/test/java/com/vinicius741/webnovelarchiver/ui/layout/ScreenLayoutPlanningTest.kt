package com.vinicius741.webnovelarchiver.ui.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the responsive layout engine, ported 1:1 from the legacy React Native
 * `useScreenLayout` hook tests (src/hooks/common/__tests__/useScreenLayout.test.ts). These cases
 * lock in the exact fold/width behaviour the RN app had, so the native port is faithful.
 *
 * Defaults mirror the hook's test setup: AUTO mode, no detected folding feature.
 */
class ScreenLayoutPlanningTest {
    private fun layout(
        width: Int,
        height: Int,
        hasFoldingFeature: Boolean = false,
        mode: ScreenLayoutMode = ScreenLayoutMode.AUTO,
    ) = ScreenLayout(widthDp = width, heightDp = height, hasFoldingFeature = hasFoldingFeature, mode = mode)

    @Test
    fun treatsFoldCoverWindowAsCompactInAutoMode() {
        val result = resolveScreenLayout(layout(411, 960))
        assertEquals(WidthClass.COMPACT, result.widthClass)
        assertEquals(HeightClass.EXPANDED, result.heightClass)
        assertEquals(1, result.numColumns)
        assertFalse(result.isLargeScreen)
        assertFalse(result.isTwoPane)
        assertFalse(result.isCompactHeight)
    }

    @Test
    fun promotesSquareIshInnerFoldWindowBelow600dpToMedium() {
        val result = resolveScreenLayout(layout(560, 760))
        assertEquals(WidthClass.MEDIUM, result.widthClass)
        assertEquals(HeightClass.MEDIUM, result.heightClass)
        assertEquals(2, result.numColumns)
        assertTrue(result.isLargeScreen)
        assertTrue(result.isTwoPane)
    }

    @Test
    fun promotesNarrowerInnerFoldLikeWindowToMedium() {
        val result = resolveScreenLayout(layout(480, 760))
        assertEquals(WidthClass.MEDIUM, result.widthClass)
        assertEquals(HeightClass.MEDIUM, result.heightClass)
        assertEquals(2, result.numColumns)
        assertTrue(result.isTwoPane)
    }

    @Test
    fun classifiesUnfoldedFoldPortraitWindowAsMedium() {
        val result = resolveScreenLayout(layout(768, 832))
        assertEquals(WidthClass.MEDIUM, result.widthClass)
        assertEquals(HeightClass.MEDIUM, result.heightClass)
        assertEquals(2, result.numColumns)
        assertTrue(result.isLargeScreen)
        assertTrue(result.isTwoPane)
    }

    @Test
    fun classifiesUnfoldedFoldLandscapeWindowAsExpanded() {
        val result = resolveScreenLayout(layout(968, 720))
        assertEquals(WidthClass.EXPANDED, result.widthClass)
        assertEquals(HeightClass.MEDIUM, result.heightClass)
        assertEquals(3, result.numColumns)
        assertTrue(result.isLargeScreen)
        assertTrue(result.isTwoPane)
    }

    @Test
    fun dropsMediumWidthToOneColumnWhenHeightIsCompact() {
        val result = resolveScreenLayout(layout(700, 420))
        assertEquals(WidthClass.MEDIUM, result.widthClass)
        assertEquals(HeightClass.COMPACT, result.heightClass)
        assertTrue(result.isCompactHeight)
        assertEquals(1, result.numColumns)
        assertFalse(result.isTwoPane)
    }

    @Test
    fun keepsSplitScreenMediumWindowsInTwoColumnsWhenHeightIsSufficient() {
        val result = resolveScreenLayout(layout(700, 900))
        assertEquals(WidthClass.MEDIUM, result.widthClass)
        assertEquals(HeightClass.EXPANDED, result.heightClass)
        assertEquals(2, result.numColumns)
        assertTrue(result.isTwoPane)
    }

    @Test
    fun usesTheSameLayoutForTabletLikeWindows() {
        val result = resolveScreenLayout(layout(768, 1024))
        assertEquals(WidthClass.MEDIUM, result.widthClass)
        assertEquals(2, result.numColumns)
        assertTrue(result.isTwoPane)
    }

    @Test
    fun updatesFromFoldedToUnfoldedDimensions() {
        val folded = resolveScreenLayout(layout(390, 844))
        assertEquals(WidthClass.COMPACT, folded.widthClass)
        assertEquals(1, folded.numColumns)

        val unfolded = resolveScreenLayout(layout(768, 832))
        assertEquals(WidthClass.MEDIUM, unfolded.widthClass)
        assertEquals(HeightClass.MEDIUM, unfolded.heightClass)
        assertEquals(2, unfolded.numColumns)
        assertTrue(unfolded.isTwoPane)
    }

    @Test
    fun handlesZeroDimensionsGracefully() {
        val result = resolveScreenLayout(layout(0, 0))
        assertEquals(WidthClass.COMPACT, result.widthClass)
        assertEquals(HeightClass.COMPACT, result.heightClass)
        assertEquals(1, result.numColumns)
    }

    @Test
    fun respectsThe600And840WidthThresholds() {
        assertEquals(WidthClass.COMPACT, resolveScreenLayout(layout(399, 1100)).widthClass)
        assertEquals(1, resolveScreenLayout(layout(399, 1100)).numColumns)

        val medium = resolveScreenLayout(layout(600, 900))
        assertEquals(WidthClass.MEDIUM, medium.widthClass)
        assertEquals(2, medium.numColumns)

        val expanded = resolveScreenLayout(layout(840, 900))
        assertEquals(WidthClass.EXPANDED, expanded.widthClass)
        assertEquals(3, expanded.numColumns)
    }

    @Test
    fun doesNotPromoteTallNarrowCoverLikeWindowsBelow600dp() {
        val result = resolveScreenLayout(layout(390, 844))
        assertEquals(WidthClass.COMPACT, result.widthClass)
        assertEquals(1, result.numColumns)
        assertFalse(result.isTwoPane)
    }

    @Test
    fun forcesCoverModeToCompact() {
        val result = resolveScreenLayout(layout(768, 1024, mode = ScreenLayoutMode.COVER))
        assertEquals(WidthClass.COMPACT, result.widthClass)
        assertEquals(1, result.numColumns)
        assertFalse(result.isTwoPane)
    }

    @Test
    fun forcesInnerModeToMediumOnTheCoverWindow() {
        val result = resolveScreenLayout(layout(411, 960, mode = ScreenLayoutMode.INNER))
        assertEquals(WidthClass.MEDIUM, result.widthClass)
        assertEquals(2, result.numColumns)
        assertTrue(result.isTwoPane)
    }

    @Test
    fun forcesInnerModeToExpandedAtOrAbove840dp() {
        val result = resolveScreenLayout(layout(968, 720, mode = ScreenLayoutMode.INNER))
        assertEquals(WidthClass.EXPANDED, result.widthClass)
        assertEquals(3, result.numColumns)
    }

    @Test
    fun usesNativeFoldDetectionInAutoMode() {
        val result = resolveScreenLayout(layout(540, 760, hasFoldingFeature = true))
        assertTrue(result.hasFoldingFeature)
        assertEquals(WidthClass.MEDIUM, result.widthClass)
        assertEquals(2, result.numColumns)
        assertTrue(result.isTwoPane)
    }

    @Test
    fun modeFromStoredNormalizesInvalidValuesToAuto() {
        assertEquals(ScreenLayoutMode.AUTO, ScreenLayoutMode.fromStored(null))
        assertEquals(ScreenLayoutMode.AUTO, ScreenLayoutMode.fromStored("legacy"))
        assertEquals(ScreenLayoutMode.COVER, ScreenLayoutMode.fromStored("cover"))
        assertEquals(ScreenLayoutMode.INNER, ScreenLayoutMode.fromStored("inner"))
    }

    @Test
    fun libraryMaxContentWidthMapsColumnCounts() {
        assertEquals(760, libraryMaxContentWidth(1))
        assertEquals(1040, libraryMaxContentWidth(2))
        assertEquals(1320, libraryMaxContentWidth(3))
    }

    @Test
    fun readerSidePaddingKeepsCompactTextCloseToTheScreenEdges() {
        assertEquals(8, readerSidePadding(WidthClass.COMPACT))
        assertEquals(20, readerSidePadding(WidthClass.MEDIUM))
        assertEquals(32, readerSidePadding(WidthClass.EXPANDED))
    }
}
