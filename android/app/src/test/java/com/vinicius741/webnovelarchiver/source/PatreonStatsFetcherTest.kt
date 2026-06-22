package com.vinicius741.webnovelarchiver.source

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PatreonStatsFetcherTest {
    @Test
    fun `normalizes regular and creator-world Patreon links`() {
        assertEquals("https://www.patreon.com/writer/about", PatreonStatsFetcher.aboutUrl("https://patreon.com/writer/posts"))
        assertEquals("https://www.patreon.com/writer/about", PatreonStatsFetcher.aboutUrl("https://www.patreon.com/cw/writer"))
        assertEquals("https://www.patreon.com/writer/about", PatreonStatsFetcher.aboutUrl("https://www.patreon.com/c/writer"))
    }

    @Test
    fun `parses public members earnings and tiers from Patreon JSON payload`() {
        val html =
            """
            <script type="application/json">
              {"campaign":{"paid_member_count":42,"campaign_pledge_sum":123400,"currency":"USD","show_earnings":true},
               "tiers":[{"amount_cents":500,"currency":"USD","is_free_tier":false,"patron_count":42}]}
            </script>
            """.trimIndent()

        val result = PatreonStatsFetcher.parseCampaign(html)

        assertEquals(42, result.paidMembers)
        assertEquals(123_400L, result.exactAmountCents)
        assertEquals("USD", result.exactAmountCurrency)
        assertEquals(PatreonTierSnapshot(500, "USD", 42), result.tiers.single())
    }

    @Test
    fun `parses hidden campaign tier counts from escaped creator-world payload`() {
        val html =
            """payload={\"amount_cents\":0,\"currency\":\"USD\",\"is_free_tier\":true,\"patron_count\":50},""" +
                """{\"patron_count\":12,\"is_free_tier\":false,\"currency\":\"USD\",\"amount_cents\":1000}"""

        val result = PatreonStatsFetcher.parseCampaign(html)

        assertNull(result.paidMembers)
        assertNull(result.exactAmountCents)
        assertEquals(PatreonTierSnapshot(1_000, "USD", 12), result.tiers.single())
    }

    @Test
    fun `fetch wires exact public Patreon statistics`() =
        runBlocking {
            val fetcher =
                PatreonStatsFetcher(
                    fetchPage = {
                        """<script type="application/json">{"campaign":{"paid_member_count":42,"campaign_pledge_sum":123400,"currency":"USD","show_earnings":true}}</script>"""
                    },
                    now = { 123L },
                )

            val stats = fetcher.fetch("https://patreon.com/writer")!!

            assertEquals(42, stats.paidMembers)
            assertEquals(123_400L, stats.monthlyUsdCents)
            assertFalse(stats.amountIsEstimated)
            assertEquals(123L, stats.updatedAt)
        }

    @Test
    fun `fetch estimates hidden earnings from current tier distribution`() =
        runBlocking {
            val fetcher =
                PatreonStatsFetcher(
                    fetchPage = {
                        """<script type="application/json">{"campaign":{"paid_member_count":10,"show_earnings":false},"tiers":[{"amount_cents":500,"currency":"USD","is_free_tier":false,"patron_count":4},{"amount_cents":1000,"currency":"USD","is_free_tier":false,"patron_count":6}]}</script>"""
                    },
                    now = { 456L },
                )

            val stats = fetcher.fetch("https://patreon.com/writer")!!

            assertEquals(10, stats.paidMembers)
            assertEquals(7_200L, stats.monthlyUsdCents)
            assertTrue(stats.amountIsEstimated)
            assertEquals(456L, stats.updatedAt)
        }

    @Test
    fun `fetch returns null instead of throwing when currency conversion is unavailable`() =
        runBlocking {
            val fetcher =
                PatreonStatsFetcher(
                    fetchPage = { url ->
                        if (url.contains("frankfurter")) {
                            "{\"error\":\"unsupported\"}"
                        } else {
                            """<script type="application/json">{"campaign":{"paid_member_count":10,"campaign_pledge_sum":10000,"currency":"XYZ","show_earnings":true}}</script>"""
                        }
                    },
                )

            assertNull(fetcher.fetch("https://patreon.com/writer"))
        }
}
