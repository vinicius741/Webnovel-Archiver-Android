package com.vinicius741.webnovelarchiver.narration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NarrationServerPlanningTest {
    @Test
    fun addressesAcceptAHostAnOptionalPortAndHttp() {
        assertEquals(NarrationServerEndpoint("192.168.1.20", 8790), NarrationServerEndpoint.parse(" 192.168.1.20 "))
        assertEquals(NarrationServerEndpoint("100.64.0.2", 9000), NarrationServerEndpoint.parse("100.64.0.2:9000"))
        assertEquals(
            NarrationServerEndpoint("my-mac.tail1234.ts.net", 8790),
            NarrationServerEndpoint.parse("http://my-mac.tail1234.ts.net/"),
        )
        assertEquals(NarrationServerEndpoint("fd7a::1", 8790), NarrationServerEndpoint.parse("[fd7a::1]"))
        assertEquals("[fd7a::1]:8790", NarrationServerEndpoint.parse("[fd7a::1]")!!.display)
    }

    @Test
    fun addressesRejectOtherSchemesPathsAndBadPorts() {
        listOf("", "https://mac", "mac/v1", "mac:0", "mac:70000", "mac:port", "a:b:c", "my mac").forEach {
            assertNull(it, NarrationServerEndpoint.parse(it))
        }
    }

    @Test
    fun onlyTheSameModelAndProtocolAreUsable() {
        val good = NarrationServerInfo(protocol = 1, model = "kokoro-en-v0_19", workers = 4)
        assertNull(NarrationServerPlanning.incompatibility(good, "Mac"))
        assertNotNull(NarrationServerPlanning.incompatibility(good.copy(model = "kokoro-multi-lang-v1_0"), "Mac"))
        assertNotNull(NarrationServerPlanning.incompatibility(good.copy(protocol = 2), "Mac"))
    }

    @Test
    fun inFlightIsOnePerModelCopyPlusOneWithinBounds() {
        assertEquals(2, NarrationServerPlanning.inFlight(NarrationServerInfo(workers = 0)))
        assertEquals(5, NarrationServerPlanning.inFlight(NarrationServerInfo(workers = 4)))
        assertEquals(NarrationServerPlanning.MAX_IN_FLIGHT, NarrationServerPlanning.inFlight(NarrationServerInfo(workers = 32)))
    }

    @Test
    fun theFormNeedsANameAnAddressAndTheToken() {
        val token = "abcd-efgh-jkmn"
        assertNull(NarrationServerPlanning.formError("Mac", "192.168.1.20", "", token))
        assertNull(NarrationServerPlanning.formError("Mac", "", "100.64.0.2", token))
        assertNull(NarrationServerPlanning.formError("Mac", "192.168.1.20", "100.64.0.2:9000", token))
        assertNotNull(NarrationServerPlanning.formError("", "192.168.1.20", "", token))
        assertNotNull(NarrationServerPlanning.formError("Mac", "", " ", token))
        assertNotNull(NarrationServerPlanning.formError("Mac", "https://mac", "", token))
        assertNotNull(NarrationServerPlanning.formError("Mac", "192.168.1.20", "mac/v1", token))
        assertNotNull(NarrationServerPlanning.formError("Mac", "192.168.1.20", "", "short"))
        assertEquals("abcd-efgh-jkmn", NarrationServerPlanning.normalizeToken(" ABCD-efgh-JKMN "))
    }

    @Test
    fun theLocalNetworkIsTriedBeforeTailscale() {
        val server = NarrationServer(id = "m", name = "Mac", address = "192.168.1.20", tailscaleAddress = "100.64.0.2")
        assertEquals(
            listOf(
                NarrationServerRoute(NarrationRoute.LOCAL, NarrationServerEndpoint("192.168.1.20", 8790)),
                NarrationServerRoute(NarrationRoute.TAILSCALE, NarrationServerEndpoint("100.64.0.2", 8790)),
            ),
            NarrationServerPlanning.routes(server),
        )
        // Saved before the Tailscale field existed: one route, as before.
        assertEquals(listOf(NarrationRoute.LOCAL), NarrationServerPlanning.routes(server.copy(tailscaleAddress = "")).map { it.route })
        assertEquals(listOf(NarrationRoute.TAILSCALE), NarrationServerPlanning.routes(server.copy(address = "")).map { it.route })
        // The same address twice is tried once.
        assertEquals(1, NarrationServerPlanning.routes(server.copy(tailscaleAddress = "192.168.1.20:8790")).size)
    }

    @Test
    fun onlyAnAddressWithAFallbackGetsTheShortConnectTimeout() {
        assertEquals(NarrationServerPlanning.FALLBACK_CONNECT_TIMEOUT_MS, NarrationServerPlanning.connectTimeoutMs(0, 2))
        assertNull(NarrationServerPlanning.connectTimeoutMs(1, 2))
        assertNull(NarrationServerPlanning.connectTimeoutMs(0, 1))
    }

    @Test
    fun aServerThatAnsweredExplainsMoreThanOneThatWasUnreachable() {
        val unreachable = NarrationServerProblem("Can't reach Mac", transient = true)
        val rejected = NarrationServerRejected("Mac rejected the token")
        assertEquals(unreachable, NarrationServerPlanning.failure("Mac", listOf(unreachable)))
        assertEquals(rejected, NarrationServerPlanning.failure("Mac", listOf(unreachable, rejected)))
        val both = NarrationServerPlanning.failure("Mac", listOf(unreachable, unreachable))
        assertTrue(both.transient)
        assertTrue(both.message!!.contains("local network or over Tailscale"))
        assertTrue(NarrationServerPlanning.failure("Mac", emptyList()).message!!.contains("no valid address"))
    }

    @Test
    fun aStatusIsCheckedAgainWhenOldOrMadeForOtherSettings() {
        val status = NarrationServerStatus("key", checkedAtMs = 1_000, online = true)
        assertTrue(NarrationServerPlanning.needsCheck(null, "key", 1_000, 30_000))
        assertFalse(NarrationServerPlanning.needsCheck(status, "key", 30_999, 30_000))
        assertTrue(NarrationServerPlanning.needsCheck(status, "key", 31_000, 30_000))
        assertTrue(NarrationServerPlanning.needsCheck(status, "edited", 1_000, 30_000))
    }

    @Test
    fun statusLinesSayWhetherAndHowAComputerAnswers() {
        assertEquals("Checking…", NarrationServerPlanning.statusLine(null, generatingHere = false))
        val online = NarrationServerStatus("k", 0, online = true, route = NarrationRoute.LOCAL)
        assertEquals("Online · local network · generating here", NarrationServerPlanning.statusLine(online, generatingHere = true))
        assertEquals(
            "Online · Tailscale",
            NarrationServerPlanning.statusLine(online.copy(route = NarrationRoute.TAILSCALE), generatingHere = false),
        )
        val stopped = online.copy(problem = NarrationServerPlanning.memoryProblem("critical"))
        assertEquals("Online · local network · stopped, short of memory", NarrationServerPlanning.statusLine(stopped, false))
        assertEquals(NarrationServerTone.WARNING, NarrationServerPlanning.tone(stopped))
        assertNull(NarrationServerPlanning.memoryProblem("low"))
        val offline = NarrationServerStatus("k", 0, online = false, problem = "Can't reach Mac")
        assertEquals("Offline", NarrationServerPlanning.statusLine(offline, generatingHere = false))
        assertEquals(NarrationServerTone.OFFLINE, NarrationServerPlanning.tone(offline))
        assertEquals(NarrationServerTone.CHECKING, NarrationServerPlanning.tone(null))
        assertEquals(NarrationServerTone.ONLINE, NarrationServerPlanning.tone(online))
    }

    @Test
    fun theRowListsBothAddresses() {
        val server = NarrationServer(address = "192.168.1.20", tailscaleAddress = "100.64.0.2")
        assertEquals("192.168.1.20 · Tailscale 100.64.0.2", NarrationServerPlanning.addressesLine(server))
        assertEquals("Tailscale 100.64.0.2", NarrationServerPlanning.addressesLine(server.copy(address = "")))
    }

    @Test
    fun theTestSummarySaysHowTheComputerWasReached() {
        val info = NarrationServerInfo(protocol = 1, model = "kokoro-en-v0_19", workers = 4, name = "mac")
        val tailscale = NarrationServerRoute(NarrationRoute.TAILSCALE, NarrationServerEndpoint("100.64.0.2", 8790))
        assertEquals(
            "Connected to mac over Tailscale · kokoro-en-v0_19 · 4 model copies · generates 6.0x real time",
            NarrationServerPlanning.testSummary(info, tailscale, audioMs = 6_000, realMs = 1_000),
        )
    }

    @Test
    fun chipsToggleComputersInAndThePhoneStandsInOnlyBesideThem() {
        val phone = GenerateOnChoice()
        val mac = NarrationServerPlanning.tapGenerateOn(phone, "mac")
        assertEquals(GenerateOnChoice(listOf("mac")), mac)
        assertFalse(mac.phoneChosen)
        val both = NarrationServerPlanning.tapGenerateOn(mac, "bazzite")
        assertEquals(listOf("mac", "bazzite"), both.serverIds)
        val withPhone = NarrationServerPlanning.tapGenerateOn(both, null)
        assertTrue(withPhone.phoneFallback && withPhone.phoneChosen)
        assertEquals(both, NarrationServerPlanning.tapGenerateOn(withPhone, null))
        // Dropping a computer keeps the others and the fallback; dropping the last leaves this phone alone.
        val bazzite = NarrationServerPlanning.tapGenerateOn(withPhone, "mac")
        assertEquals(GenerateOnChoice(listOf("bazzite"), phoneFallback = true), bazzite)
        assertEquals(phone, NarrationServerPlanning.tapGenerateOn(bazzite, "bazzite"))
        // "This phone" alone stays chosen when tapped.
        assertEquals(phone, NarrationServerPlanning.tapGenerateOn(phone, null))
        assertTrue(phone.phoneChosen)
    }

    @Test
    fun whenEveryComputerIsOutTheProblemToFixIsReportedOverUnreachableOnes() {
        val offline = NarrationServerProblem("Can't reach Mac", transient = true)
        val alsoOffline = NarrationServerProblem("Can't reach Bazzite", transient = true)
        val token = NarrationServerRejected("Bazzite rejected the token. Check it in Audio generation.")
        assertEquals(offline, NarrationServerPlanning.poolFailure(listOf("Mac" to offline)))
        assertEquals(token, NarrationServerPlanning.poolFailure(listOf("Mac" to offline, "Bazzite" to token)))
        assertEquals(
            "Can't reach Mac, Bazzite or Studio. Check that they are on and the server is running",
            NarrationServerPlanning.poolFailure(listOf("Mac" to offline, "Bazzite" to alsoOffline, "Studio" to offline)).message,
        )
    }

    @Test
    fun theLocationChangesWithTheChosenComputersAndTheFallback() {
        val mac = NarrationServer("mac", "Mac", "192.168.1.20", "token-1")
        val bazzite = NarrationServer("bazzite", "Bazzite", "192.168.1.30", "token-2")
        assertNull(NarrationPlaces().locationKey)
        assertNull(NarrationPlaces(phoneFallback = true).locationKey)
        val both = NarrationPlaces(listOf(mac, bazzite)).locationKey
        assertEquals("${mac.locationKey},${bazzite.locationKey}", both)
        assertEquals("$both,phone", NarrationPlaces(listOf(mac, bazzite), phoneFallback = true).locationKey)
    }
}
