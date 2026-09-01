package com.vinicius741.webnovelarchiver.source

import com.vinicius741.webnovelarchiver.domain.model.PatreonRawTier
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        // real-charge fields must win so capture converts the campaign's true currency.
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
    fun `fetch captures exact public statistics in usd`() =
        runBlocking {
            val fetcher =
                PatreonStatsFetcher(
                    fetchPage = {
                        """<script type="application/json">{"campaign":{"paid_member_count":42,"campaign_pledge_sum":123400,"currency":"USD","show_earnings":true}}</script>"""
                    },
                    now = { 123L },
                )

            val raw = fetcher.fetch("https://patreon.com/writer")!!

            assertEquals(123L, raw.capturedAt)
            assertEquals(42, raw.paidMembers)
            assertEquals(123_400L, raw.exactMonthlyUsdCents)
            // The story-level block always carries a resolved ladder, here empty.
            assertEquals(emptyList<PatreonRawTier>(), raw.tiers)
        }

    @Test
    fun `fetch captures raw tier prices and counts without deriving a dollar figure`() =
        runBlocking {
            val fetcher =
                PatreonStatsFetcher(
                    fetchPage = {
                        """<script type="application/json">{"campaign":{"paid_member_count":10,"show_earnings":false},"tiers":[{"amount_cents":500,"currency":"USD","is_free_tier":false,"patron_count":4},{"amount_cents":1000,"currency":"USD","is_free_tier":false,"patron_count":6}]}</script>"""
                    },
                    now = { 456L },
                )

            val raw = fetcher.fetch("https://patreon.com/writer")!!

            assertEquals(456L, raw.capturedAt)
            assertEquals(10, raw.paidMembers)
            assertNull(raw.exactMonthlyUsdCents)
            assertEquals(listOf(PatreonRawTier(500, 4), PatreonRawTier(1_000, 6)), raw.tiers)
        }

    @Test
    fun `fetch converts tier prices to usd once per distinct currency`() =
        runBlocking {
            // Mirrors The Wixx Chronicles: about page exposes only tiers (no counts), then the
            // campaign API fills in a real paid_member_count and BRL tier prices.
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
            val fxCalls = mutableListOf<String>()
            val fetcher =
                PatreonStatsFetcher(
                    fetchPage = { url ->
                        when {
                            url.contains("/about") -> aboutHtml
                            url.contains("/api/campaigns/") -> apiJson
                            url.contains("frankfurter") -> {
                                fxCalls += url
                                frankfurter
                            }
                            else -> ""
                        }
                    },
                    now = { 789L },
                )

            val raw = fetcher.fetch("https://patreon.com/RileyCLyle")!!

            // Real paid count and USD-converted price are captured raw; nothing is estimated here.
            assertEquals(95, raw.paidMembers)
            assertEquals(106, raw.totalMembers)
            assertNull(raw.exactMonthlyUsdCents)
            assertEquals(listOf(PatreonRawTier(usdCents = 400, members = null)), raw.tiers)
            assertEquals(1, fxCalls.size)
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

            // Losing the figure to an FX outage must read as fetch failure so the sync keeps the
            // previously stored stats instead of replacing them with a members-only block.
            assertNull(fetcher.fetch("https://patreon.com/writer"))
        }

    @Test
    fun `fetch returns null when conversion drops the only ladder`() =
        runBlocking {
            // Hidden earnings + a single BRL tier + FX outage: no usable dollar source remains.
            val fetcher =
                PatreonStatsFetcher(
                    fetchPage = { url ->
                        if (url.contains("frankfurter")) {
                            "{\"error\":\"unsupported\"}"
                        } else {
                            """<script type="application/json">{"campaign":{"paid_member_count":95,"show_earnings":false},"tiers":[{"amount_cents":2000,"patron_currency":"BRL","currency":"BRL","is_free_tier":false}]}</script>"""
                        }
                    },
                )

            assertNull(fetcher.fetch("https://patreon.com/writer"))
        }

    @Test
    fun `fetch captures members-only stats when earnings are hidden and no paid tiers exist`() =
        runBlocking {
            val fetcher =
                PatreonStatsFetcher(
                    fetchPage = {
                        """<script type="application/json">{"campaign":{"paid_member_count":10,"patron_count":40,"show_earnings":false},"tiers":[{"amount_cents":0,"currency":"USD","is_free_tier":true,"patron_count":40}]}</script>"""
                    },
                    now = { 111L },
                )

            val raw = fetcher.fetch("https://patreon.com/writer")!!

            assertEquals(111L, raw.capturedAt)
            assertEquals(10, raw.paidMembers)
            assertEquals(40, raw.totalMembers)
            assertNull(raw.exactMonthlyUsdCents)
            assertEquals(emptyList<PatreonRawTier>(), raw.tiers)
        }

    @Test
    fun `fetch keeps measured zeros for public earnings and members`() =
        runBlocking {
            // A small creator publicly showing $0 with 0 paid members: zeros are measurements, not
            // "not measured" — otherwise the estimator would fabricate an assumed figure.
            val fetcher =
                PatreonStatsFetcher(
                    fetchPage = {
                        """<script type="application/json">{"campaign":{"paid_member_count":0,"patron_count":12,"campaign_pledge_sum":0,"currency":"USD","show_earnings":true},"tiers":[{"amount_cents":300,"currency":"USD","is_free_tier":false,"patron_count":0}]}</script>"""
                    },
                    now = { 222L },
                )

            val raw = fetcher.fetch("https://patreon.com/writer")!!

            assertEquals(0, raw.paidMembers)
            assertEquals(12, raw.totalMembers)
            assertEquals(0L, raw.exactMonthlyUsdCents)
            assertEquals(listOf(PatreonRawTier(usdCents = 300, members = 0)), raw.tiers)
        }
}
