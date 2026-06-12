package com.vinicius741.webnovelarchiver.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadErrorClassifierTest {
    @Test
    fun classifiesRateLimitHttpErrorsAsRetryable() {
        val classified = DownloadErrorClassifier.classify(IllegalStateException("HTTP 429 for chapter"))

        assertEquals("rate_limit", classified.category)
        assertEquals("429", classified.code)
        assertTrue(classified.retryable)
    }

    @Test
    fun classifiesUnknownErrorsAsTerminal() {
        val classified = DownloadErrorClassifier.classify(IllegalStateException("Unexpected parser failure"))

        assertEquals("unknown", classified.category)
        assertFalse(classified.retryable)
    }

    @Test
    fun retriesUntilMaxRetryCountIsExceeded() {
        val job = DownloadJob(id = "job", retryCount = 3, maxRetries = 3)
        val retryable = ClassifiedDownloadError("Network", "network", "NETWORK_ERROR", retryable = true)

        assertTrue(DownloadErrorClassifier.shouldAutoRetry(job, retryable))
        job.retryCount = 4
        assertFalse(DownloadErrorClassifier.shouldAutoRetry(job, retryable))
    }

    @Test
    fun computesCappedExponentialRetryDelay() {
        assertEquals(3000L, DownloadErrorClassifier.retryDelayMs(DownloadJob(retryCount = 1)))
        assertEquals(6000L, DownloadErrorClassifier.retryDelayMs(DownloadJob(retryCount = 2)))
        assertEquals(60000L, DownloadErrorClassifier.retryDelayMs(DownloadJob(retryCount = 10)))
    }
}
