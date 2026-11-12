package com.vinicius741.webnovelarchiver.narration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        assertNull(NarrationServerPlanning.formError("Mac", "192.168.1.20", "abcd-efgh-jkmn"))
        assertNotNull(NarrationServerPlanning.formError("", "192.168.1.20", "abcd-efgh-jkmn"))
        assertNotNull(NarrationServerPlanning.formError("Mac", "https://mac", "abcd-efgh-jkmn"))
        assertNotNull(NarrationServerPlanning.formError("Mac", "192.168.1.20", "short"))
        assertEquals("abcd-efgh-jkmn", NarrationServerPlanning.normalizeToken(" ABCD-efgh-JKMN "))
    }
}
