package com.vinicius741.webnovelarchiver.source

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.vinicius741.webnovelarchiver.domain.model.PatreonStats
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.URI
import kotlin.math.roundToLong

internal data class PatreonTierSnapshot(
    val amountCents: Long,
    val currency: String,
    val members: Int?,
)

internal data class PatreonCampaignSnapshot(
    val paidMembers: Int?,
    val exactAmountCents: Long?,
    val exactAmountCurrency: String?,
    val tiers: List<PatreonTierSnapshot>,
)

/** Reads the creator's public Patreon page. No creator names or novel ids are special-cased. */
class PatreonStatsFetcher internal constructor(
    private val fetchPage: suspend (String) -> String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    constructor(
        network: NetworkClient,
        now: () -> Long = System::currentTimeMillis,
    ) : this(
        fetchPage = { url -> network.fetch(url, PATREON_CALL_TIMEOUT_MILLIS) },
        now = now,
    )

    suspend fun fetch(creatorUrl: String): PatreonStats? {
        val html = fetchPage(aboutUrl(creatorUrl))
        val campaign = parseCampaign(html)
        val paidMembers =
            campaign.paidMembers ?: campaign.tiers
                .mapNotNull { it.members }
                .sum()
                .takeIf { it > 0 } ?: return null
        val exactAmount =
            campaign.exactAmountCents?.let { cents ->
                val currency = campaign.exactAmountCurrency ?: campaign.tiers.firstOrNull()?.currency ?: "USD"
                convertToUsd(cents, currency)
            }
        val monthlyUsdCents = exactAmount ?: estimateMonthlyUsd(campaign, paidMembers) ?: return null
        return PatreonStats(
            paidMembers = paidMembers,
            monthlyUsdCents = monthlyUsdCents,
            amountIsEstimated = exactAmount == null,
            updatedAt = now(),
        )
    }

    private suspend fun estimateMonthlyUsd(
        campaign: PatreonCampaignSnapshot,
        paidMembers: Int,
    ): Long? {
        val paidTiers = campaign.tiers.filter { it.amountCents > 0 }
        if (paidTiers.isEmpty()) return null
        val knownTiers = paidTiers.filter { it.members != null && it.members > 0 }
        val grossUsdCents =
            if (knownTiers.isNotEmpty()) {
                val converted = knownTiers.mapNotNull { tier -> convertToUsd(tier.amountCents, tier.currency)?.let { tier to it } }
                if (converted.isEmpty()) return null
                val knownGross = converted.sumOf { (tier, usdCents) -> usdCents * (tier.members ?: 0) }
                val convertedMembers = converted.sumOf { (tier, _) -> tier.members ?: 0 }
                if (convertedMembers == paidMembers) knownGross else (knownGross.toDouble() / convertedMembers * paidMembers).roundToLong()
            } else {
                val sortedPrices = paidTiers.mapNotNull { tier -> convertToUsd(tier.amountCents, tier.currency) }.sorted()
                if (sortedPrices.isEmpty()) return null
                sortedPrices[sortedPrices.lastIndex / 2] * paidMembers
            }
        // Patreon public earnings are an approximation after platform/payment fees. Apply the same
        // conservative 10% deduction when only tier-derived gross revenue is available.
        return (grossUsdCents * 0.9).roundToLong()
    }

    private suspend fun convertToUsd(
        cents: Long,
        currency: String,
    ): Long? {
        if (currency.equals("USD", ignoreCase = true)) return cents
        val rate =
            runCatching {
                val response = fetchPage("https://api.frankfurter.app/latest?from=${currency.uppercase()}&to=USD")
                JsonParser
                    .parseString(response)
                    .asJsonObject["rates"]
                    ?.asJsonObject
                    ?.get("USD")
                    ?.asDouble
            }.getOrNull() ?: return null
        return (cents * rate).roundToLong()
    }

    companion object {
        internal fun aboutUrl(url: String): String {
            val path = runCatching { URI(url).path }.getOrNull().orEmpty()
            val segments = path.split('/').filter { it.isNotBlank() }
            val creator =
                if (segments.firstOrNull().equals("cw", ignoreCase = true) ||
                    segments.firstOrNull().equals("c", ignoreCase = true)
                ) {
                    segments.getOrNull(1)
                } else {
                    segments.firstOrNull()
                }
            require(!creator.isNullOrBlank()) { "Invalid Patreon creator URL" }
            return "https://www.patreon.com/$creator/about"
        }

        internal fun parseCampaign(html: String): PatreonCampaignSnapshot {
            var paidMembers: Int? = null
            var exactAmount: Long? = null
            var exactCurrency: String? = null
            val tiers = mutableListOf<PatreonTierSnapshot>()
            Jsoup
                .parse(html)
                .select("script[type=application/json]")
                .forEach { script ->
                    runCatching { JsonParser.parseString(script.data()) }.getOrNull()?.let { root ->
                        visitJson(root) { objectValue ->
                            objectValue.int("paid_member_count")?.takeIf { it > 0 }?.let { paidMembers = it }
                            val pledge = objectValue.long("campaign_pledge_sum")
                            if (pledge != null && objectValue.boolean("show_earnings") == true) {
                                exactAmount = pledge
                                exactCurrency = objectValue.string("currency") ?: objectValue.string("campaign_pledge_sum_currency")
                            }
                            if (objectValue.boolean("is_free_tier") == false) {
                                val amount = objectValue.long("amount_cents")
                                val currency = objectValue.string("currency")
                                if (amount != null && currency != null) {
                                    tiers += PatreonTierSnapshot(amount, currency, objectValue.int("patron_count"))
                                }
                            }
                        }
                    }
                }
            val normalized = Parser.unescapeEntities(html, false).replace("\\\"", "\"")
            if (paidMembers == null) {
                paidMembers =
                    Regex("\"paid_member_count\"\\s*:\\s*(\\d+)")
                        .find(normalized)
                        ?.groupValues
                        ?.get(1)
                        ?.toIntOrNull()
            }
            if (exactAmount == null && Regex("\"show_earnings\"\\s*:\\s*true").containsMatchIn(normalized)) {
                exactAmount =
                    Regex("\"campaign_pledge_sum\"\\s*:\\s*(\\d+)")
                        .find(normalized)
                        ?.groupValues
                        ?.get(1)
                        ?.toLongOrNull()
            }
            if (tiers.isEmpty()) tiers += parseEscapedTiers(normalized)
            return PatreonCampaignSnapshot(paidMembers, exactAmount, exactCurrency, tiers.distinct())
        }

        private fun parseEscapedTiers(html: String): List<PatreonTierSnapshot> =
            Regex("\"is_free_tier\"\\s*:\\s*false")
                .findAll(html)
                .mapNotNull { marker ->
                    val start = html.lastIndexOf('{', marker.range.first)
                    val end = html.indexOf('}', marker.range.last + 1)
                    if (start < 0 || end < 0) return@mapNotNull null
                    val tierObject = html.substring(start, end + 1)
                    val amount = jsonLong(tierObject, "amount_cents") ?: return@mapNotNull null
                    val currency = jsonString(tierObject, "currency") ?: return@mapNotNull null
                    PatreonTierSnapshot(amount, currency, jsonLong(tierObject, "patron_count")?.toInt())
                }.toList()

        private fun jsonLong(
            value: String,
            key: String,
        ): Long? =
            Regex("\"${Regex.escape(key)}\"\\s*:\\s*(\\d+)")
                .find(value)
                ?.groupValues
                ?.get(1)
                ?.toLongOrNull()

        private fun jsonString(
            value: String,
            key: String,
        ): String? = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"([^\"]+)\"").find(value)?.groupValues?.get(1)

        private fun visitJson(
            element: JsonElement,
            visitor: (JsonObject) -> Unit,
        ) {
            when {
                element.isJsonObject -> {
                    val objectValue = element.asJsonObject
                    visitor(objectValue)
                    objectValue.entrySet().forEach { (_, child) -> visitJson(child, visitor) }
                }
                element.isJsonArray -> element.asJsonArray.forEach { child -> visitJson(child, visitor) }
            }
        }

        private fun JsonObject.int(key: String): Int? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt

        private fun JsonObject.long(key: String): Long? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong

        private fun JsonObject.boolean(key: String): Boolean? =
            get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

        private fun JsonObject.string(key: String): String? =
            get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

        private const val PATREON_CALL_TIMEOUT_MILLIS = 12_000L
    }
}
