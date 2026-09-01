package com.vinicius741.webnovelarchiver.domain.metrics

import com.vinicius741.webnovelarchiver.domain.model.PatreonRawStats
import com.vinicius741.webnovelarchiver.domain.model.PatreonRawTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PatreonEarningsPlanningTest {
    @Test
    fun `public pledge sum passes through untouched`() {
        val earnings =
            PatreonEarningsPlanning.estimate(
                PatreonRawStats(paidMembers = 42, exactMonthlyUsdCents = 123_400),
            )!!

        assertEquals(42, earnings.paidMembers)
        assertEquals(123_400L, earnings.monthlyUsdCents)
        assertFalse(earnings.amountIsEstimated)
        assertFalse(earnings.membersIsEstimated)
        assertNull(earnings.floorUsdCents)
    }

    @Test
    fun `public earnings fall back to total members when paid count is hidden`() {
        val earnings =
            PatreonEarningsPlanning.estimate(
                PatreonRawStats(totalMembers = 50, exactMonthlyUsdCents = 9_900),
            )!!

        assertEquals(50, earnings.paidMembers)
        assertFalse(earnings.membersIsEstimated)
        assertEquals(9_900L, earnings.monthlyUsdCents)
    }

    @Test
    fun `public earnings without any member signal yield nothing`() {
        assertNull(PatreonEarningsPlanning.estimate(PatreonRawStats(exactMonthlyUsdCents = 9_900)))
    }

    @Test
    fun `hidden earnings with per-tier counts use the count-weighted gross`() {
        val earnings =
            PatreonEarningsPlanning.estimate(
                PatreonRawStats(
                    paidMembers = 10,
                    tiers = listOf(PatreonRawTier(500, 4), PatreonRawTier(1_000, 6)),
                ),
            )!!

        // (500×4 + 1000×6) = 8000 gross; × 0.9 fee = 7200.
        assertEquals(7_200L, earnings.monthlyUsdCents)
        assertTrue(earnings.amountIsEstimated)
        assertFalse(earnings.membersIsEstimated)
        // A measured distribution is trustworthy enough that no floor range is shown.
        assertNull(earnings.floorUsdCents)
    }

    @Test
    fun `weighted gross scales to the paid total when tier counts only cover part of it`() {
        val earnings =
            PatreonEarningsPlanning.estimate(
                PatreonRawStats(
                    paidMembers = 20,
                    tiers = listOf(PatreonRawTier(500, 4), PatreonRawTier(1_000, 6)),
                ),
            )!!

        // 8000 / 10 × 20 = 16000; × 0.9 = 14400.
        assertEquals(14_400L, earnings.monthlyUsdCents)
    }

    @Test
    fun `hidden earnings without tier counts use the median not the mean`() {
        // LunaWolve's real ladder shape (USD at capture, rounded): entry tiers ~$6 up to a $590
        // meme tier. The mean-of-prices heuristic once turned this into a $121k/month estimate for
        // a 1,137-member campaign; the median keeps it in a plausible range.
        val ladder =
            listOf(
                PatreonRawTier(628),
                PatreonRawTier(628),
                PatreonRawTier(1_208),
                PatreonRawTier(1_788),
                PatreonRawTier(1_788),
                PatreonRawTier(2_368),
                PatreonRawTier(5_945),
                PatreonRawTier(59_018),
            )
        val earnings = PatreonEarningsPlanning.estimate(PatreonRawStats(paidMembers = 1_137, tiers = ladder))!!

        // Median 1788c × 1137 × 0.9 = 1,829,660c ≈ $18.3k — not the ~$121k the mean produced.
        assertEquals(1_829_660L, earnings.monthlyUsdCents)
        assertTrue(earnings.amountIsEstimated)
        // Cheapest-tier bound: 628 × 1137 × 0.9 = 642,632c ≈ $6.4k.
        assertEquals(642_632L, earnings.floorUsdCents)
    }

    @Test
    fun `median of an even ladder averages the two middle prices`() {
        val earnings =
            PatreonEarningsPlanning.estimate(
                PatreonRawStats(paidMembers = 100, tiers = listOf(PatreonRawTier(1_000), PatreonRawTier(3_000))),
            )!!

        // Median 2000 × 100 × 0.9 = 180000.
        assertEquals(180_000L, earnings.monthlyUsdCents)
        assertEquals(90_000L, earnings.floorUsdCents)
    }

    @Test
    fun `single-tier ladder has no floor spread`() {
        val earnings =
            PatreonEarningsPlanning.estimate(
                PatreonRawStats(paidMembers = 100, tiers = listOf(PatreonRawTier(1_000))),
            )!!

        assertEquals(90_000L, earnings.monthlyUsdCents)
        assertNull(earnings.floorUsdCents)
    }

    @Test
    fun `paid members fall back to tier counts then to seventy percent of the total`() {
        val fromTiers =
            PatreonEarningsPlanning.estimate(
                PatreonRawStats(totalMembers = 100, tiers = listOf(PatreonRawTier(1_000, 30))),
            )!!
        assertEquals(30, fromTiers.paidMembers)
        assertFalse(fromTiers.membersIsEstimated)

        val assumed =
            PatreonEarningsPlanning.estimate(
                PatreonRawStats(totalMembers = 100, tiers = listOf(PatreonRawTier(1_000))),
            )!!
        assertEquals(70, assumed.paidMembers)
        assertTrue(assumed.membersIsEstimated)
        // 70 × 1000 × 0.9 = 63000.
        assertEquals(63_000L, assumed.monthlyUsdCents)
    }

    @Test
    fun `measured members with no tiers and no exact amount show members only`() {
        val earnings = PatreonEarningsPlanning.estimate(PatreonRawStats(paidMembers = 1_137))!!

        assertEquals(1_137, earnings.paidMembers)
        assertNull(earnings.monthlyUsdCents)
        assertTrue(earnings.amountIsEstimated)
    }

    @Test
    fun `legacy derived-only stats deserialize into members-only raw data`() {
        // Old story JSON (paidMembers + monthlyUsdCents + flags) read into PatreonRawStats: the
        // measured member count survives; the derived dollar figure must not.
        val legacyJson =
            """{"paidMembers":1137,"monthlyUsdCents":12102569,"amountIsEstimated":true,"updatedAt":1788200677538,"membersIsEstimated":false}"""
        val raw =
            com.google.gson
                .Gson()
                .fromJson(legacyJson, PatreonRawStats::class.java)

        val earnings = PatreonEarningsPlanning.estimate(raw)!!
        assertEquals(1_137, earnings.paidMembers)
        assertNull(earnings.monthlyUsdCents)
    }

    @Test
    fun `measured zeros pass through instead of triggering assumptions`() {
        // Public $0 earnings with 0 paid members: zeros are measurements, so no 70%-of-total or
        // median-price fabrication is allowed on top of them.
        val earnings =
            PatreonEarningsPlanning.estimate(
                PatreonRawStats(paidMembers = 0, totalMembers = 12, exactMonthlyUsdCents = 0),
            )!!

        assertEquals(0, earnings.paidMembers)
        assertEquals(0L, earnings.monthlyUsdCents)
        assertFalse(earnings.amountIsEstimated)
        assertFalse(earnings.membersIsEstimated)
    }

    @Test
    fun `measured zero members cap a hidden-earnings estimate at zero`() {
        val earnings =
            PatreonEarningsPlanning.estimate(
                PatreonRawStats(paidMembers = 0, tiers = listOf(PatreonRawTier(1_000), PatreonRawTier(3_000))),
            )!!

        assertEquals(0, earnings.paidMembers)
        // Median price × 0 members × 0.9 = $0 — the ladder cannot invent paying members.
        assertEquals(0L, earnings.monthlyUsdCents)
    }

    @Test
    fun `nothing measurable yields null`() {
        assertNull(PatreonEarningsPlanning.estimate(PatreonRawStats()))
        assertNull(PatreonEarningsPlanning.estimate(PatreonRawStats(tiers = listOf(PatreonRawTier(1_000)))))
    }
}
