package com.vinicius741.webnovelarchiver.source.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class SourceReliabilityStoreTest {
    @Test
    fun roundTripsPersistedHostStateThroughTheDocument() {
        val directory = Files.createTempDirectory("reliability").toFile()
        val states =
            listOf(
                PersistedHostReliability(
                    key = "scribble_hub",
                    canonicalHost = "scribblehub.com",
                    manualVerificationRequired = true,
                    cooldownUntil = Long.MAX_VALUE,
                    browserTransportUntil = 1_234_567_890L,
                    adaptiveMinimumGapMillis = 3_000L,
                    requestCount = 214L,
                    challengeCount = 3L,
                    rateLimitCount = 1L,
                    browserRenderCount = 38L,
                ),
            )

        SourceReliabilityStore(directory).save(states)

        assertEquals(states, SourceReliabilityStore(directory).load())
    }

    @Test
    fun missingOrCorruptDocumentLoadsAsEmpty() {
        val directory = Files.createTempDirectory("reliability-missing").toFile()

        assertEquals(emptyList<PersistedHostReliability>(), SourceReliabilityStore(directory).load())

        directory.resolve("source_reliability.json").writeText("not json {")
        assertEquals(emptyList<PersistedHostReliability>(), SourceReliabilityStore(directory).load())
        assertTrue(directory.resolve("source_reliability.json").exists())
    }
}
