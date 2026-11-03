package com.vinicius741.webnovelarchiver.simulation

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.vinicius741.webnovelarchiver.ai.DecisionClient
import com.vinicius741.webnovelarchiver.ai.DescriptionEvidencePlanning
import com.vinicius741.webnovelarchiver.ai.OpenRouterClient
import com.vinicius741.webnovelarchiver.ai.OpenRouterMessage
import com.vinicius741.webnovelarchiver.app.ScaledAppTime
import com.vinicius741.webnovelarchiver.source.RoyalRoadProvider
import com.vinicius741.webnovelarchiver.source.network.CloudflareBypassInterceptor
import com.vinicius741.webnovelarchiver.source.network.NetworkClient
import com.vinicius741.webnovelarchiver.source.network.SourceAccessBlockedException
import com.vinicius741.webnovelarchiver.source.network.SourceReliabilityCoordinator
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class SimulationTest {
    @get:Rule val temporary = TemporaryFolder()

    private val catalog: String get() = javaClass.getResourceAsStream("/simulation/catalog.json")!!.bufferedReader().readText()

    private fun config(
        id: String = "ai-cover",
        extra: String = "",
    ): SimulationConfig =
        SimulationConfig.parse(
            """{"version":1,"sessionId":"test-session","scenario":"$id","epochMillis":1793620800000$extra}""".reader(),
            catalog.reader(),
        )

    private fun responder(
        config: SimulationConfig,
        directory: File = temporary.newFolder(),
    ): Pair<SimulationResponder, SimulationJournal> {
        val time = ScaledAppTime(config.epochMillis, 1000.0)
        val journal = SimulationJournal(directory, config, time)
        return SimulationResponder(config, time, journal, byteArrayOf(1, 2, 3)) to journal
    }

    private fun client(responder: SimulationResponder): OkHttpClient =
        OkHttpClient
            .Builder()
            .dns(Dns { error("Simulation reached DNS") })
            .addInterceptor(responder)
            .build()

    @Test fun unknownRequestsAndWrongMethodsFailWithoutDNS() {
        val (responder, journal) = responder(config())
        val client = client(responder)
        for (url in listOf(
            "https://example.com/",
            "https://openrouter.ai/api/v1/unexpected",
            SimulationFixtures.STORY_URL + "?tracking=1",
        )) {
            assertTrue(runCatching { client.newCall(Request.Builder().url(url).build()).execute() }.exceptionOrNull() is IOException)
        }
        assertEquals(3, journal.deniedCount())
        assertFalse(File(journal.directory, "report.json").readText().contains("example.com"))
        assertEquals("failed", journal.phase())
    }

    @Test fun realOpenRouterParsersConsumeAllSimulatedEndpoints() =
        runBlocking {
            val (responder, _) = responder(config())
            val router = OpenRouterClient(client = client(responder))
            assertTrue(router.fetchModels().any { it.id == "simulation/text" })
            assertTrue(router.fetchDecisionModels().any { it.id == "simulation/decisions" })
            assertTrue(router.fetchImageModels().any { it.id == "simulation/image" })
            assertEquals("0.0123", router.fetchCurrentKeyUsage("dummy").usage)
            val text = router.chatCompletion("dummy", "simulation/text", listOf(OpenRouterMessage("user", "Synopsis")), 2000)
            assertEquals(SimulationFixtures.DESCRIPTION, text.content)
            assertEquals("0.000042", text.receipt.costUsd)
            assertEquals(3, router.generateImage("dummy", "simulation/image", "A cover").bytes.size)
        }

    @Test fun decisionRetriesUsePerRequestCursorAndDoNotLeakSecrets() =
        runBlocking {
            val (responder, journal) = responder(config("ai-decisions-retry"))
            val router = OpenRouterClient(client = client(responder))
            val request =
                JsonParser
                    .parseString(
                        """{"model":"simulation/decisions","state":{"passage":"PRIVATE CHAPTER"},"questions":{"protagonist":{"type":"score"},"spoiler":{"type":"noul"}}}""",
                    ).asJsonObject
            val statuses = mutableListOf<Int>()
            val answer = DecisionClient(router, {}).evaluate("PRIVATE KEY", request) { _, code -> statuses += code }
            assertEquals(listOf(429, 200), statuses)
            assertEquals(1.0, DecisionClient.score(answer, "protagonist"), 0.0)
            assertEquals(0.0, DecisionClient.noul(answer, "spoiler"), 0.0)
            val report = File(journal.directory, "report.json").readText()
            assertFalse(report.contains("PRIVATE"))
            assertFalse(report.contains("Authorization"))
        }

    @Test fun cloudflareManualVerificationPausesThenUsesStickyBrowserTransport() =
        runBlocking {
            val config = config("downloads-verification")
            val (responder, journal) = responder(config)
            val reliability = SourceReliabilityCoordinator()
            val client =
                OkHttpClient
                    .Builder()
                    .dns(Dns { error("Simulation reached DNS") })
                    .addInterceptor(CloudflareBypassInterceptor(responder, reliability))
                    .addInterceptor(responder)
                    .build()
            val network = NetworkClient(client, reliabilityCoordinator = reliability)
            val chapter =
                SimulationFixtures
                    .stories(6)
                    .first()
                    .chapters
                    .first()
            assertTrue(runCatching { network.fetch(chapter.url) }.exceptionOrNull() is SourceAccessBlockedException)
            assertTrue(network.isSourceBlocked("royal_road"))
            journal.verify("www.royalroad.com")
            network.clearSourceAccess(chapter.url)
            val html = network.fetch(chapter.url)
            assertTrue(RoyalRoadProvider.parseChapterContent(html).contains("Mara"))
            assertFalse(network.isSourceBlocked("royal_road"))
            assertTrue(network.reliabilitySnapshots().single().browserTransportActive)
        }

    @Test fun cursorsSurviveRestartAndMarkIncompleteJobs() {
        val config = config()
        val directory = temporary.newFolder()
        val (_, first) = responder(config, directory)
        assertEquals(1, first.nextAttempt("image-key"))
        first.phase("held")
        first.markAutoStarted()
        val (_, restored) = responder(config, directory)
        assertEquals(2, restored.nextAttempt("image-key"))
        assertEquals("interrupted", restored.phase())
        assertTrue(restored.autoStarted())
    }

    @Test fun malformedSessionsNeverResolveToLiveMode() {
        for (extra in listOf(
            ",\"speed\":0",
            ",\"speed\":1001",
            ",\"hold\":\"unknown\"",
            ",\"timing\":\"controlled\"",
            ",\"unexpected\":true",
        )) {
            assertTrue(runCatching { config(extra = extra) }.isFailure)
        }
        assertTrue(runCatching { SimulationConfig.parse("{}".reader(), catalog.reader()) }.isFailure)
        assertNotNull(config("ai-description"))
    }

    @Test fun replayMatchesFormFieldsAndQueryOrderWithoutAcceptingUnknownRequests() {
        val manifest = """{"version":1,"storyUrls":[],"routes":[{
            "method":"POST","url":"https://fixture.invalid/ajax?a=1&b=2",
            "form":{"page":"1"},"asset":"simulation/replay/test.html"}]}"""
        val replay = SimulationReplay(manifest.reader()) { "fixture body".toByteArray() }

        fun request(page: String) =
            Request
                .Builder()
                .url("https://fixture.invalid/ajax?b=2&a=1")
                .post(
                    okhttp3.FormBody
                        .Builder()
                        .add("page", page)
                        .build(),
                ).build()
        assertNotNull(replay.match(request("1")))
        assertEquals(null, replay.match(request("2")))
        assertEquals(null, replay.match(Request.Builder().url("https://fixture.invalid/ajax?a=1&b=2").build()))
    }

    @Test fun cancellingAHeldRequestReleasesTheWorker() {
        val (responder, journal) = responder(config(extra = ",\"hold\":\"source\""))
        val call = client(responder).newCall(Request.Builder().url(SimulationFixtures.STORY_URL).build())
        val executor =
            java.util.concurrent.Executors
                .newSingleThreadExecutor()
        try {
            val result = executor.submit<Throwable?> { runCatching { call.execute().close() }.exceptionOrNull() }
            val deadline = System.nanoTime() + 2_000_000_000L
            while (journal.phase() != "held" && System.nanoTime() < deadline) Thread.sleep(10)
            assertEquals("held", journal.phase())
            call.cancel()
            assertTrue(result.get(2, java.util.concurrent.TimeUnit.SECONDS) is IOException)
            assertTrue(File(journal.directory, "report.json").readText().contains("cancelled"))
        } finally {
            call.cancel()
            executor.shutdownNow()
        }
    }
}
