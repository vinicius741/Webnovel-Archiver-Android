package com.vinicius741.webnovelarchiver.ui.layout

import org.junit.Assert.assertEquals
import org.junit.Test

class ActionSpacingPlanningTest {
    @Test
    fun actionBelowContentGetsTheFullGap() {
        assertEquals(42, ActionSpacingPlanning.topMarginPx(42, PrecedingView.Content(spaceBelowPx = 0)))
    }

    @Test
    fun spaceTheContentAlreadyLeavesCountsTowardTheGap() {
        assertEquals(21, ActionSpacingPlanning.topMarginPx(42, PrecedingView.Content(spaceBelowPx = 21)))
    }

    @Test
    fun contentThatAlreadyLeavesEnoughSpaceAddsNothing() {
        assertEquals(0, ActionSpacingPlanning.topMarginPx(42, PrecedingView.Content(spaceBelowPx = 60)))
    }

    @Test
    fun firstActionInAContainerAddsNothing() {
        assertEquals(0, ActionSpacingPlanning.topMarginPx(42, PrecedingView.None))
    }

    @Test
    fun anExplicitSpacerIsLeftInCharge() {
        assertEquals(0, ActionSpacingPlanning.topMarginPx(42, PrecedingView.Spacer))
    }
}
