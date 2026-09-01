package com.vinicius741.webnovelarchiver.domain.metrics

import com.vinicius741.webnovelarchiver.domain.model.PatreonRawStats
import com.vinicius741.webnovelarchiver.domain.model.PatreonRawTier
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The single earnings formula, applied at render to raw Patreon measurements. Because nothing
 * derived is persisted, changing this object re-derives every stored snapshot and every displayed
 * figure at once. Formulas:
 * - Public earnings: the pledge sum as captured.
 * - Hidden earnings, per-tier counts published: count-weighted gross, scaled to the paid total.
 * - Hidden earnings, counts hidden: median tier price — the mean is poisoned by joke/whale tiers
 *   (a R$3,052 meme tier once estimated a real campaign at 7–20x its plausible earnings).
 * Estimates deduct 10% for Patreon's cut, matching what the site's public figures approximate.
 */
object PatreonEarningsPlanning {
    /** Share of public total members assumed to be paying when no paid count is available. */
    private const val PAID_MEMBER_ASSUMPTION = 0.70

    private const val PLATFORM_FEE = 0.9

    data class PatreonEarnings(
        val paidMembers: Int,
        val membersIsEstimated: Boolean,
        /** Null when the amount cannot be estimated at all (members may still be known). */
        val monthlyUsdCents: Long?,
        val amountIsEstimated: Boolean,
        /** Cheapest-tier bound shown as a range next to the estimate; null when there is no meaningful spread. */
        val floorUsdCents: Long? = null,
    )

    fun estimate(raw: PatreonRawStats): PatreonEarnings? {
        // Zeros are measurements (a campaign publicly showing $0 / 0 paid members), not absence:
        // only a null field means Patreon did not report it. Tiers with no positive price are not
        // paid tiers, so those stay filtered.
        val paid = raw.paidMembers
        val total = raw.totalMembers
        val tiers = raw.tiers.orEmpty().filter { it.usdCents > 0 }
        val exact = raw.exactMonthlyUsdCents

        if (exact != null) {
            val members = paid ?: total ?: return null
            return PatreonEarnings(
                paidMembers = members,
                membersIsEstimated = false,
                monthlyUsdCents = exact,
                amountIsEstimated = false,
            )
        }

        val tierMemberSum = tiers.mapNotNull { it.members }.sum().takeIf { it > 0 }
        val members: Int
        val membersEstimated: Boolean
        when {
            paid != null -> {
                members = paid
                membersEstimated = false
            }
            tierMemberSum != null -> {
                members = tierMemberSum
                membersEstimated = false
            }
            total != null -> {
                members = (total * PAID_MEMBER_ASSUMPTION).roundToInt()
                membersEstimated = true
            }
            else -> return null
        }

        if (tiers.isEmpty()) {
            return PatreonEarnings(members, membersEstimated, monthlyUsdCents = null, amountIsEstimated = true)
        }

        val knownTiers = tiers.filter { (it.members ?: 0) > 0 }
        val gross: Long =
            if (knownTiers.isNotEmpty()) {
                val knownGross = knownTiers.sumOf { it.usdCents * it.members!! }
                val knownMembers = knownTiers.sumOf { it.members!! }
                if (knownMembers == members) knownGross else (knownGross.toDouble() / knownMembers * members).roundToLong()
            } else {
                medianPrice(tiers) * members
            }
        val monthly = (gross * PLATFORM_FEE).roundToLong()
        val floor =
            if (knownTiers.isEmpty()) {
                val bound = (tiers.minOf { it.usdCents } * members * PLATFORM_FEE).roundToLong()
                bound.takeIf { it < monthly }
            } else {
                null
            }
        return PatreonEarnings(members, membersEstimated, monthly, amountIsEstimated = true, floorUsdCents = floor)
    }

    /** Median of tier prices; patrons cluster at the bottom of real ladders, so this is the least-biased price for an unknown distribution. */
    private fun medianPrice(tiers: List<PatreonRawTier>): Long {
        val prices = tiers.map { it.usdCents }.sorted()
        val mid = prices.size / 2
        return if (prices.size % 2 == 1) prices[mid] else Math.round((prices[mid - 1] + prices[mid]) / 2.0)
    }
}
