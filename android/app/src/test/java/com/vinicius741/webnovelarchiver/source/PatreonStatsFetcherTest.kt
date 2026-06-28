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
    fun `extracts campaign id from about page og image`() {
        val html = """<meta property="og:image" content="https://www.patreon.com/ig/card-teaser-image/creator/15734387.png?v=abc"/>"""
        assertEquals("15734387", PatreonStatsFetcher.extractCampaignId(html))
    }

    @Test
    fun `extracts campaign id from escaped json blob`() {
        val html = """{\"campaign\":{\"id\":\"42\",\"type\":\"campaign\"}"""
        assertEquals("42", PatreonStatsFetcher.extractCampaignId(html))
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
    fun `parseCampaign prefers real charge price over usd-normalized amount`() {
        // Mirrors the real hidden-earnings about page: tiers carry a USD-normalized `amount_cents`
        // alongside the real `patron_amount_cents`/`patron_currency` the patron actually pays. The
        // real-charge fields must win so currency conversion uses the campaign's true currency.
        val html =
            """<script type="application/json">""" +
                """{"reward":{"amount_cents":300,"currency":"USD","is_free_tier":false,""" +
                """"patron_amount_cents":2000,"patron_currency":"BRL","patron_count":null}}</script>"""

        val result = PatreonStatsFetcher.parseCampaign(html)

        assertEquals(PatreonTierSnapshot(2_000, "BRL", null), result.tiers.single())
    }

    @Test
    fun `parseCampaignApi reads members and real-charge tiers from JSON-API response`() {
        val json =
            """
            {"data":{"id":"15734387","type":"campaign","attributes":{
               "paid_member_count":95,"patron_count":106,
               "earnings_visibility":"private","pledge_sum_currency":"BRL","currency":"USD"}},
             "included":[
               {"id":"-1","type":"reward","attributes":{"amount_cents":0,"currency":"USD",
                 "is_free_tier":true,"patron_amount_cents":500,"patron_currency":"BRL","patron_count":null}},
               {"id":"28371438","type":"reward","attributes":{"amount_cents":300,"currency":"USD",
                 "is_free_tier":false,"patron_amount_cents":2000,"patron_currency":"BRL","patron_count":null}}
             ]}
            """.trimIndent()

        val result = PatreonStatsFetcher.parseCampaignApi(json)!!

        assertEquals(95, result.paidMembers)
        assertEquals(106, result.totalMembers)
        assertNull(result.exactAmountCents)
        assertEquals("BRL", result.exactAmountCurrency)
        // Only the paid tier survives filtering; its real-charge BRL price is preserved.
        assertEquals(PatreonTierSnapshot(2_000, "BRL", null), result.tiers.single())
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
            assertFalse(stats.membersIsEstimated)
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
            assertFalse(stats.membersIsEstimated)
            assertEquals(456L, stats.updatedAt)
        }

    @Test
    fun `fetch estimates hidden earnings via campaign api with real paid count`() =
        runBlocking {
            // Mirrors The Wixx Chronicles: about page exposes only tiers (no counts), then the
            // campaign API fills in a real paid_member_count and the BRL tier price.
            val aboutHtml =
                """<meta property="og:image" content="https://www.patreon.com/ig/card-teaser-image/creator/15734387.png"/>'""" +
                    """<script type="application/json">{"reward":{"amount_cents":300,"currency":"USD","is_free_tier":false,""" +
                    """"patron_amount_cents":2000,"patron_currency":"BRL","patron_count":null}}</script>"""
            val apiJson =
                """{"data":{"id":"15734387","type":"campaign","attributes":{"paid_member_count":95,"patron_count":106,""" +
                    """"earnings_visibility":"private","pledge_sum_currency":"BRL"}},""" +
                    """"included":[{"id":"28371438","type":"reward","attributes":{"amount_cents":300,"currency":"USD",""" +
                    """"is_free_tier":false,"patron_amount_cents":2000,"patron_currency":"BRL","patron_count":null}}]}"""
            val frankfurter = """{"rates":{"USD":0.20}}""" // 1 BRL = 0.20 USD → R$20 = $4
            val fetcher =
                PatreonStatsFetcher(
                    fetchPage = { url ->
                        when {
                            url.contains("/about") -> aboutHtml
                            url.contains("/api/campaigns/") -> apiJson
                            url.contains("frankfurter") -> frankfurter
                            else -> ""
                        }
                    },
                    now = { 789L },
                )

            val stats = fetcher.fetch("https://patreon.com/RileyCLyle")!!

            // Real paid count reads as measured; revenue = 95 × $4 × 0.9 fee = $342.00 = 34200c.
            assertEquals(95, stats.paidMembers)
            assertEquals(34_200L, stats.monthlyUsdCents)
            assertTrue(stats.amountIsEstimated)
            assertFalse(stats.membersIsEstimated)
            assertEquals(789L, stats.updatedAt)
        }

    @Test
    fun `fetch assumes a share of total members when paid count is hidden everywhere`() =
        runBlocking {
            // Worst case: the API exposes only the public total member count and no per-tier counts,
            // and the about page carries only a tier price. We fall back to the 70% assumption for
            // members and the mean tier price for revenue.
            val aboutHtml =
                """<meta property="og:image" content="https://www.patreon.com/ig/card-teaser-image/creator/99.png"/>""" +
                    """<script type="application/json">{"reward":{"amount_cents":1000,"currency":"USD","is_free_tier":false,"patron_count":null}}</script>"""
            val apiJson =
                """{"data":{"id":"99","type":"campaign","attributes":{"patron_count":100,""" +
                    """"earnings_visibility":"private","pledge_sum_currency":"USD"}},"included":[]}"""
            val fetcher =
                PatreonStatsFetcher(
                    fetchPage = { url ->
                        when {
                            url.contains("/about") -> aboutHtml
                            url.contains("/api/campaigns/") -> apiJson
                            else -> ""
                        }
                    },
                    now = { 1L },
                )

            val stats = fetcher.fetch("https://patreon.com/writer")!!

            // 100 total × 0.70 = 70 paid (estimated). 70 × $10 × 0.9 = $630.00 = 63000c.
            assertEquals(70, stats.paidMembers)
            assertEquals(63_000L, stats.monthlyUsdCents)
            assertTrue(stats.amountIsEstimated)
            assertTrue(stats.membersIsEstimated)
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
