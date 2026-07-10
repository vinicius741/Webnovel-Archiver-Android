package com.vinicius741.webnovelarchiver.download

import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import com.vinicius741.webnovelarchiver.source.network.HttpNetworkException
import com.vinicius741.webnovelarchiver.source.network.NetworkOfflineException
import com.vinicius741.webnovelarchiver.source.network.NetworkParseException
import com.vinicius741.webnovelarchiver.source.network.NetworkTimeoutException
import com.vinicius741.webnovelarchiver.source.network.RateLimitNetworkException
import com.vinicius741.webnovelarchiver.source.network.SourceAccessBlockedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException

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
    fun classifiesSourceBlockedAsTerminal() {
        val classified = DownloadErrorClassifier.classify(SourceAccessBlockedException("https://www.scribblehub.com/story"))

        assertEquals("source_blocked", classified.category)
        assertEquals("SOURCE_BLOCKED", classified.code)
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

    @Test
    fun classifiesMissingChapterContentAsRetryableParseError() {
        // Source providers now throw "Chapter content not found on page" when the content selector is
        // absent (instead of returning a placeholder string baked into the EPUB). It must route as a
        // retryable parse error so the download job can retry, rather than a terminal unknown error.
        val classified = DownloadErrorClassifier.classify(IllegalStateException("Chapter content not found on page"))

        assertEquals("parse", classified.category)
        assertEquals("CONTENT_TOO_SHORT", classified.code)
        assertTrue(classified.retryable)
    }

    @Test
    fun typedHttpAndRateLimitErrorsRemainDistinct() {
        val forbidden = DownloadErrorClassifier.classify(HttpNetworkException("https://example.test", 403))
        val limited = DownloadErrorClassifier.classify(RateLimitNetworkException("https://example.test", 403, 1_000L))

        assertEquals("network", forbidden.category)
        assertFalse(forbidden.retryable)
        assertEquals("rate_limit", limited.category)
        assertTrue(limited.retryable)
    }

    @Test
    fun typedTimeoutOfflineAndParseErrorsAreRetryable() {
        val timeout =
            DownloadErrorClassifier.classify(
                NetworkTimeoutException("https://example.test", SocketTimeoutException("timeout")),
            )
        val offline =
            DownloadErrorClassifier.classify(
                NetworkOfflineException("https://example.test", UnknownHostException("offline")),
            )
        val parse = DownloadErrorClassifier.classify(NetworkParseException("Chapter content not found on page"))

        assertEquals("TIMEOUT", timeout.code)
        assertEquals("OFFLINE", offline.code)
        assertEquals("parse", parse.category)
        assertTrue(timeout.retryable)
        assertTrue(offline.retryable)
        assertTrue(parse.retryable)
    }
}
