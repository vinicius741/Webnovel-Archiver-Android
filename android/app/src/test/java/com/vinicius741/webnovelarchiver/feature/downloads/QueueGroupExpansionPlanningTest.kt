package com.vinicius741.webnovelarchiver.feature.downloads

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueGroupExpansionPlanningTest {
    @Test
    fun smallActiveGroupExpandsAutomatically() {
        assertTrue(
            QueueGroupExpansionPlanning.shouldExpand(
                userOverride = null,
                jobCount = QueueGroupExpansionPlanning.MAX_AUTO_EXPANDED_JOBS,
                hasActive = true,
                hasFailed = false,
            ),
        )
    }

    @Test
    fun oversizedActiveGroupStartsCollapsed() {
        assertFalse(
            QueueGroupExpansionPlanning.shouldExpand(
                userOverride = null,
                jobCount = QueueGroupExpansionPlanning.MAX_AUTO_EXPANDED_JOBS + 1,
                hasActive = true,
                hasFailed = false,
            ),
        )
    }

    @Test
    fun oversizedFailedGroupStartsCollapsed() {
        assertFalse(
            QueueGroupExpansionPlanning.shouldExpand(
                userOverride = null,
                jobCount = 500,
                hasActive = false,
                hasFailed = true,
            ),
        )
    }

    @Test
    fun explicitUserChoiceWinsForAnyGroupSize() {
        assertTrue(
            QueueGroupExpansionPlanning.shouldExpand(
                userOverride = true,
                jobCount = 500,
                hasActive = false,
                hasFailed = false,
            ),
        )
        assertFalse(
            QueueGroupExpansionPlanning.shouldExpand(
                userOverride = false,
                jobCount = 1,
                hasActive = true,
                hasFailed = true,
            ),
        )
    }
}
