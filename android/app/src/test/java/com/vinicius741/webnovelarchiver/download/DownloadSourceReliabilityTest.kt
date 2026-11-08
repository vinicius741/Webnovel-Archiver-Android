package com.vinicius741.webnovelarchiver.download

import com.vinicius741.webnovelarchiver.domain.download.DownloadJobTransitions
import com.vinicius741.webnovelarchiver.domain.model.DownloadJobStatus
import com.vinicius741.webnovelarchiver.source.network.NetworkClient
import com.vinicius741.webnovelarchiver.source.network.NetworkRequestGate
import com.vinicius741.webnovelarchiver.source.network.RateLimitNetworkException
import com.vinicius741.webnovelarchiver.source.network.SourceAccessBlockedException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Worker failures and source-wide circuit transitions, applied to the persisted queue. */
class DownloadSourceReliabilityTest {
    private val royalRoad = "royal_road"
    private val scribbleHub = "scribble_hub"
    private val now = 10_000L
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private fun reliability(
        store: InMemoryDownloadQueueStore,
        acceptsWorkerResults: () -> Boolean = { true },
        onSourceBlocked: (String, String) -> Unit = { _, _ -> },
    ) = DownloadSourceReliability(store, NetworkClient(), acceptsWorkerResults, { now }, onSourceBlocked)

    @Test
    fun rateLimitedChapterRetriesLaterAndBacksOffItsWholeSourceOnly() {
        val store =
            InMemoryDownloadQueueStore(
                downloadJob("rr-1", royalRoad, status = DownloadJobStatus.Downloading.wire),
                downloadJob("rr-2", royalRoad),
                downloadJob("sh-1", scribbleHub),
            )

        val mutation =
            reliability(store).handleJobError(
                store.job("rr-1"),
                RateLimitNetworkException("https://royalroad.com/1", 429, retryAfterMillis = 30_000L),
                royalRoad,
            )

        val failed = store.job("rr-1")
        assertEquals(DownloadJobStatus.Pending.wire, failed.status)
        assertEquals(1, failed.retryCount)
        assertEquals("rate_limit", failed.errorCategory)
        // The server's Retry-After beats the 3s exponential base.
        assertEquals(now + 30_000L, failed.nextRetryAt)
        assertEquals(now + 30_000L, store.job("rr-2").nextRetryAt)
        assertNull(store.job("sh-1").nextRetryAt)
        assertEquals("rr-1", mutation?.activeJobId)
    }

    @Test
    fun aPauseThatLandedMidRequestWinsButTheSourceStillBacksOff() {
        val inFlight = downloadJob("rr-1", royalRoad, status = DownloadJobStatus.Downloading.wire)
        val store = InMemoryDownloadQueueStore(inFlight, downloadJob("rr-2", royalRoad))
        store.mutateQueueInPlace { rows -> rows.onEach { if (it.id == "rr-1") DownloadJobTransitions.pause(it) } }

        reliability(store).handleJobError(
            inFlight,
            RateLimitNetworkException("https://royalroad.com/1", 429, retryAfterMillis = 30_000L),
            royalRoad,
        )

        val paused = store.job("rr-1")
        assertEquals(DownloadJobStatus.Paused.wire, paused.status)
        assertEquals(0, paused.retryCount)
        assertNull(paused.error)
        // The 429 still happened, so the rest of the source must not hammer the site.
        assertEquals(now + 30_000L, store.job("rr-2").nextRetryAt)
    }

    @Test
    fun terminalFailureAfterRetriesAreSpentFailsTheRowWithoutTouchingSiblings() {
        val store =
            InMemoryDownloadQueueStore(
                downloadJob("rr-1", royalRoad, status = DownloadJobStatus.Downloading.wire, retryCount = 3),
                downloadJob("rr-2", royalRoad),
            )

        reliability(store).handleJobError(store.job("rr-1"), IllegalStateException("Network timeout"), royalRoad)

        val failed = store.job("rr-1")
        assertEquals(DownloadJobStatus.Failed.wire, failed.status)
        assertEquals(4, failed.retryCount)
        assertNull(failed.nextRetryAt)
        assertNull(store.job("rr-2").nextRetryAt)
    }

    @Test
    fun resultsArriveAfterTheEngineStoppedAcceptingThemAreDropped() {
        val store =
            InMemoryDownloadQueueStore(
                downloadJob("rr-1", royalRoad, status = DownloadJobStatus.Downloading.wire),
                downloadJob("rr-2", royalRoad),
            )
        val before = store.queue()
        var blockedCallbacks = 0
        // Accepted at the first check, revoked (library replaced, engine stopped) before the
        // transaction runs: the in-transaction check must still win.
        var checks = 0
        val reliability = reliability(store, acceptsWorkerResults = { checks++ == 0 }) { _, _ -> blockedCallbacks++ }

        assertNull(reliability.handleJobError(store.job("rr-1"), RateLimitNetworkException("u", 429, 30_000L), royalRoad))
        checks = 0
        assertNull(reliability.blockSourceJobs(royalRoad, SourceAccessBlockedException("https://royalroad.com/1")))

        assertEquals(before, store.queue())
        assertEquals(0, blockedCallbacks)
    }

    @Test
    fun blockingASourceFailsItsInFlightWorkDefersItsQueueAndNotifiesOnce() {
        val store =
            InMemoryDownloadQueueStore(
                downloadJob("rr-1", royalRoad, status = DownloadJobStatus.Downloading.wire),
                downloadJob("rr-2", royalRoad),
                downloadJob("sh-1", scribbleHub, status = DownloadJobStatus.Downloading.wire),
            )
        val notified = mutableListOf<Pair<String, String>>()

        reliability(store) { source, url -> notified += source to url }
            .blockSourceJobs(royalRoad, SourceAccessBlockedException("https://royalroad.com/1"))

        assertTrue(DownloadJobTransitions.isAwaitingVerification(store.job("rr-1")))
        assertEquals(DownloadJobStatus.Pending.wire, store.job("rr-2").status)
        assertEquals(now + DownloadScheduler.BLOCKED_SOURCE_RECHECK_MILLIS, store.job("rr-2").nextRetryAt)
        assertEquals(DownloadJobStatus.Downloading.wire, store.job("sh-1").status)
        assertEquals(listOf(royalRoad to "https://royalroad.com/1"), notified)
    }

    @Test
    fun bulkPreflightRunsOncePerLargeBatchAndNotForSmallOnes() =
        runBlocking {
            server.enqueue(MockResponse().setBody("<html>reader page</html>"))
            val url = server.url("/chapter").toString()
            val store = InMemoryDownloadQueueStore()
            val batch = (1..PREFLIGHT_BATCH).map { downloadJob("rr-$it", royalRoad, url = url) }
            val reliability = reliability(store)
            val gate = NetworkRequestGate { claim -> claim() }

            val small = reliability.preflightSourceIfNeeded(royalRoad, batch[0], batch.take(PREFLIGHT_BATCH - 1), gate)
            val first = reliability.preflightSourceIfNeeded(royalRoad, batch[0], batch, gate)
            val repeat = reliability.preflightSourceIfNeeded(royalRoad, batch[1], batch, gate)

            assertFalse(small.attempted)
            assertTrue(first.attempted)
            assertNull(first.mutation)
            assertFalse(repeat.attempted)
            assertEquals(1, server.requestCount)
        }

    private companion object {
        const val PREFLIGHT_BATCH = 20
    }
}
