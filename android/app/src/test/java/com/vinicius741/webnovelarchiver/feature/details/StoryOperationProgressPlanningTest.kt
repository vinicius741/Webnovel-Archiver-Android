package com.vinicius741.webnovelarchiver.feature.details

import com.vinicius741.webnovelarchiver.navigation.StoryOperationKind
import com.vinicius741.webnovelarchiver.navigation.StoryOperationState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryOperationProgressPlanningTest {
    @Test
    fun syncProgressIsAlwaysIndeterminate() {
        assertTrue(
            storyOperationIndeterminate(
                StoryOperationState("s", StoryOperationKind.SYNC, "Fetching…"),
            ),
        )
        assertTrue(
            storyOperationIndeterminate(
                StoryOperationState("s", StoryOperationKind.SYNC, "Fetching…", progress = 0.5f),
            ),
        )
    }

    @Test
    fun cleanupAndEpubAreIndeterminateUntilProgressIsSet() {
        assertTrue(
            storyOperationIndeterminate(
                StoryOperationState("s", StoryOperationKind.CLEANUP, "Processing…"),
            ),
        )
        assertFalse(
            storyOperationIndeterminate(
                StoryOperationState("s", StoryOperationKind.CLEANUP, "Processing 1/10", progress = 0.1f),
            ),
        )
        assertTrue(
            storyOperationIndeterminate(
                StoryOperationState("s", StoryOperationKind.EPUB, "Preparing…"),
            ),
        )
        assertFalse(
            storyOperationIndeterminate(
                StoryOperationState("s", StoryOperationKind.EPUB, "Generating EPUB 2/3", progress = 0.66f),
            ),
        )
    }
}
