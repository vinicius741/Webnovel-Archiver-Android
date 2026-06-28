package com.vinicius741.webnovelarchiver.source

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.vinicius741.webnovelarchiver.domain.model.PatreonStats
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.URI
import kotlin.math.roundToInt
import kotlin.math.roundToLong

internal data class PatreonTierSnapshot(
    val amountCents: Long,
    val currency: String,
    val members: Int?,
)

internal data class PatreonCampaignSnapshot(
    val paidMembers: Int?,
    val totalMembers: Int?,
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
        val aboutUrl = aboutUrl(creatorUrl)
        val aboutHtml = fetchPage(aboutUrl)
        // The public campaign API is the richest source for member counts (it returns paid/total
        // counts even for hidden-earnings campaigns, where the about-page HTML exposes nothing
        // usable). The about-page HTML is still parsed too: it reliably carries tier prices even
        // when the API's `included` array omits them, so the two are merged — counts from the API,
        // tiers from whichever source has them.
        val apiCampaign =
            runCatching { extractCampaignId(aboutHtml) }
                .getOrNull()
                ?.let { id -> runCatching { parseCampaignApi(fetchPage(campaignApiUrl(id))) }.getOrNull() }
        val htmlCampaign = parseCampaign(aboutHtml)
        val campaign =
            when {
                apiCampaign != null && apiCampaign.tiers.isNotEmpty() -> apiCampaign
                apiCampaign != null && htmlCampaign.tiers.isNotEmpty() ->
                    apiCampaign.copy(tiers = htmlCampaign.tiers)
                apiCampaign != null -> apiCampaign
                else -> htmlCampaign
            }
        return buildStats(campaign)
    }

    private suspend fun buildStats(campaign: PatreonCampaignSnapshot): PatreonStats? {
        val exactAmount =
            campaign.exactAmountCents?.let { cents ->
                val currency = campaign.exactAmountCurrency ?: campaign.tiers.firstOrNull()?.currency ?: "USD"
                convertToUsd(cents, currency)
            }
        if (exactAmount != null) {
            val paidMembers = campaign.paidMembers ?: campaign.totalMembers ?: return null
            return PatreonStats(
                paidMembers = paidMembers,
                monthlyUsdCents = exactAmount,
                amountIsEstimated = false,
                membersIsEstimated = false,
                updatedAt = now(),
            )
        }
        // Earnings are hidden: estimate revenue from members × tier prices. We always show a number
        // when a Patreon exists, even if paid-member counts are hidden — falling back to an
        // assumption about how many of the public total members are paid.
        val (paidMembers, membersEstimated) = resolvePaidMembers(campaign) ?: return null
        val monthlyUsdCents = estimateMonthlyUsd(campaign, paidMembers) ?: return null
        return PatreonStats(
            paidMembers = paidMembers,
            monthlyUsdCents = monthlyUsdCents,
            amountIsEstimated = true,
            membersIsEstimated = membersEstimated,
            updatedAt = now(),
        )
    }

    /**
     * Resolves the paid-member count to display. Returns null only when no member signal exists at
     * all (paid, per-tier, or total). Otherwise returns `(count, estimated)` — [estimated] is true
     * when the count is derived from a total-members assumption rather than read directly.
     */
    private fun resolvePaidMembers(campaign: PatreonCampaignSnapshot): Pair<Int, Boolean>? {
        campaign.paidMembers?.let { return it to false }
        val tierSum =
            campaign.tiers
                .mapNotNull { it.members }
                .sum()
                .takeIf { it > 0 }
        tierSum?.let { return it to false }
        // Last resort: assume a share of the public total member count are paying members. Used only
        // for campaigns that hide both earnings and per-tier counts, so the card still shows an
        // approximate figure instead of nothing.
        campaign.totalMembers?.let { return (it * PAID_MEMBER_ASSUMPTION).roundToInt() to true }
        return null
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
                // No per-tier member split available: take the mean of the paid tier prices. A simple
                // median over-counts when a cheap tier dominates and under-counts when a pricey one
                // does; the mean is the least-biased single number for an unknown distribution.
                val prices = paidTiers.mapNotNull { tier -> convertToUsd(tier.amountCents, tier.currency) }
                if (prices.isEmpty()) return null
                (prices.sum() / prices.size) * paidMembers
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

        internal fun campaignApiUrl(campaignId: String): String = "https://www.patreon.com/api/campaigns/$campaignId"

        /**
         * Extracts the numeric Patreon campaign id from an about page. Patreon embeds it in the
         * `og:image` URL (`/creator/15734387.png`) and inside escaped JSON blobs
         * (`\"campaign\":{\"data\":{\"id\":\"15734387\"}`); the og:image form is the most stable
         * across page revisions.
         */
        internal fun extractCampaignId(html: String): String? {
            Regex("/creator/(\\d+)\\b").find(html)?.let { return it.groupValues[1] }
            // Escaped JSON (about-page inline scripts) may nest the id under campaign.data.id or
            // expose it directly; try both escaped and unescaped forms.
            Regex(
                "\\\\\"campaign\\\\\":\\{(?:\\\\\"data\\\\\":\\{)?\\\\\"id\\\\\":\\\\\"(\\d+)\\\\\"",
            ).find(html)?.let { return it.groupValues[1] }
            Regex("\"campaign\":\\{(?:\"data\":\\{)?\"id\":\"(\\d+)\"").find(html)?.let { return it.groupValues[1] }
            return null
        }

        /**
         * Parses Patreon's public JSON:API campaign response (`/api/campaigns/{id}`). Returns null
         * when the payload doesn't carry the attributes we rely on. Tiers travel in the `included`
         * array as `reward` resources; we prefer the real charge price
         * (`patron_amount_cents`/`patron_currency`) over the USD-normalized `amount_cents`.
         */
        internal fun parseCampaignApi(json: String): PatreonCampaignSnapshot? {
            val root =
                runCatching { JsonParser.parseString(json) }.getOrNull()?.takeIf { it.isJsonObject }
                    ?: return null
            val data = root.asJsonObject.getAsJsonObject("data") ?: return null
            val attributes = data.getAsJsonObject("attributes") ?: return null
            val exactAmount: Long? =
                attributes.string("earnings_visibility")?.let { visibility ->
                    if (visibility == "public") attributes.long("pledge_sum") else null
                }
            val exactCurrency = attributes.string("pledge_sum_currency") ?: attributes.string("currency")
            val tiers = mutableListOf<PatreonTierSnapshot>()
            root.asJsonObject.getAsJsonArray("included")?.forEach { item ->
                val resource = item.asJsonObject
                if (resource.string("type") == "reward") {
                    val attrs = resource.getAsJsonObject("attributes") ?: return@forEach
                    // Skip free tiers: they carry a default `patron_amount_cents` (the suggested
                    // pledge) even though members on them pay nothing, so they must not be treated
                    // as revenue tiers.
                    if (attrs.boolean("is_free_tier") == true) return@forEach
                    val amount =
                        attrs.long("patron_amount_cents") ?: attrs.long("amount_cents") ?: return@forEach
                    val currency = attrs.string("patron_currency") ?: attrs.string("currency") ?: return@forEach
                    tiers += PatreonTierSnapshot(amount, currency, attrs.int("patron_count"))
                }
            }
            val paidTiers = tiers.filter { it.amountCents > 0 }
            return PatreonCampaignSnapshot(
                paidMembers = attributes.int("paid_member_count"),
                totalMembers = attributes.int("patron_count"),
                exactAmountCents = exactAmount,
                exactAmountCurrency = exactCurrency,
                tiers = (paidTiers.ifEmpty { tiers }).distinct(),
            )
        }

        internal fun parseCampaign(html: String): PatreonCampaignSnapshot {
            var paidMembers: Int? = null
            var totalMembers: Int? = null
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
                            objectValue.int("patron_count")?.takeIf { it > 0 }?.let {
                                if (totalMembers == null) totalMembers = it
                            }
                            val pledge = objectValue.long("campaign_pledge_sum")
                            if (pledge != null && objectValue.boolean("show_earnings") == true) {
                                exactAmount = pledge
                                exactCurrency = objectValue.string("currency") ?: objectValue.string("campaign_pledge_sum_currency")
                            }
                            if (objectValue.boolean("is_free_tier") == false) {
                                val amount =
                                    objectValue.long("patron_amount_cents") ?: objectValue.long("amount_cents")
                                val currency = objectValue.string("patron_currency") ?: objectValue.string("currency")
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
                        ?.takeIf { it > 0 }
            }
            if (totalMembers == null) {
                totalMembers =
                    Regex("\"patron_count\"\\s*:\\s*(\\d+)")
                        .find(normalized)
                        ?.groupValues
                        ?.get(1)
                        ?.toIntOrNull()
                        ?.takeIf { it > 0 }
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
            return PatreonCampaignSnapshot(paidMembers, totalMembers, exactAmount, exactCurrency, tiers.distinct())
        }

        private fun parseEscapedTiers(html: String): List<PatreonTierSnapshot> =
            Regex("\"is_free_tier\"\\s*:\\s*false")
                .findAll(html)
                .mapNotNull { marker ->
                    val start = html.lastIndexOf('{', marker.range.first)
                    val end = html.indexOf('}', marker.range.last + 1)
                    if (start < 0 || end < 0) return@mapNotNull null
                    val tierObject = html.substring(start, end + 1)
                    val amount =
                        jsonLong(tierObject, "patron_amount_cents") ?: jsonLong(tierObject, "amount_cents") ?: return@mapNotNull null
                    val currency =
                        jsonString(tierObject, "patron_currency") ?: jsonString(tierObject, "currency") ?: return@mapNotNull null
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

        /** Share of public total members assumed to be paying when no paid count is available. */
        private const val PAID_MEMBER_ASSUMPTION = 0.70

        private const val PATREON_CALL_TIMEOUT_MILLIS = 12_000L
    }
}
