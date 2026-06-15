package com.vinicius741.webnovelarchiver.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultCleanupTest {
    @Test
    fun defaultSentenceListMatchesReactNativeAssetShape() {
        assertEquals(81, DefaultCleanup.sentences.size)
        assertEquals(
            "If you come across this story on Amazon, it's taken without permission from the author. Report it.",
            DefaultCleanup.sentences.first(),
        )
        assertEquals(
            "This story has been taken without authorization. Report any sightings.",
            DefaultCleanup.sentences.last(),
        )
        assertTrue(DefaultCleanup.sentences.contains("Reading on Amazon or a pirate site? This novel is from Royal Road. Support the author by reading it there."))
    }
}
