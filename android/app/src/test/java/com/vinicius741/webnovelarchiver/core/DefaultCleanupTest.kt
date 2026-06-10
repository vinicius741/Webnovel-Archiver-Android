package com.vinicius741.webnovelarchiver.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultCleanupTest {
    @Test
    fun defaultSentenceListMatchesReactNativeAssetShape() {
        assertEquals(54, DefaultCleanup.sentences.size)
        assertEquals(
            "Stolen from its rightful author, this tale is not meant to be on Amazon; report any sightings.",
            DefaultCleanup.sentences.first(),
        )
        assertEquals(
            "This story has been taken without authorization. Report any sightings.",
            DefaultCleanup.sentences.last(),
        )
        assertTrue(DefaultCleanup.sentences.contains("Reading on Amazon or a pirate site? This novel is from Royal Road. Support the author by reading it there."))
    }
}
