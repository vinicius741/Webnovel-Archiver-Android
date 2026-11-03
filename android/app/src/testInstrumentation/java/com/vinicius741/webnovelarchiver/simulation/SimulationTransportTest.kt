package com.vinicius741.webnovelarchiver.simulation

import com.vinicius741.webnovelarchiver.ai.OpenRouterClient
import com.vinicius741.webnovelarchiver.ai.OpenRouterMessage
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Real socket behavior stays separate from accelerated script time. All servers bind locally. */
class SimulationTransportTest {
    private val completion = """{
        "id":"local-fixture",
        "choices":[{"finish_reason":"stop","message":{"content":"${SimulationFixtures.DESCRIPTION}"}}]
    }"""

    @Test fun localRedirectAndDelayedBodyExerciseTheRealHttpClient() =
        runBlocking {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(307).addHeader("Location", server.url("/redirected")))
                server.enqueue(MockResponse().setBody(completion).setBodyDelay(30, TimeUnit.MILLISECONDS))
                val result = OpenRouterClient(server.url("/").toString()).chatCompletion("dummy", "simulation/text", messages(), 2000)
                assertEquals(SimulationFixtures.DESCRIPTION, result.content)
                assertEquals("POST", server.takeRequest().method)
                assertEquals("/redirected", server.takeRequest().path)
            }
        }

    @Test fun socketTimeoutProducesFailureWithinTheTestDeadline() =
        runBlocking {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
                val client =
                    OkHttpClient
                        .Builder()
                        .callTimeout(100, TimeUnit.MILLISECONDS)
                        .retryOnConnectionFailure(false)
                        .build()
                val failure =
                    runCatching {
                        OpenRouterClient(server.url("/").toString(), client).chatCompletion("dummy", "simulation/text", messages(), 2000)
                    }.exceptionOrNull()
                assertTrue(failure is IOException)
                assertEquals(1, server.requestCount)
            }
        }

    @Test fun truncatedSocketResponseCannotBecomeASuccessfulDraft() =
        runBlocking {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody(completion.repeat(100)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
                val failure =
                    runCatching {
                        OpenRouterClient(server.url("/").toString()).chatCompletion("dummy", "simulation/text", messages(), 2000)
                    }.exceptionOrNull()
                assertTrue(failure is IOException)
            }
        }

    private fun messages() = listOf(OpenRouterMessage("user", "Describe the fixture"))
}
