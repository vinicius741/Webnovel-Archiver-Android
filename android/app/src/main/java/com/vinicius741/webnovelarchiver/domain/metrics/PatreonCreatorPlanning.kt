package com.vinicius741.webnovelarchiver.domain.metrics

import com.vinicius741.webnovelarchiver.domain.model.PatreonRawStats
import com.vinicius741.webnovelarchiver.domain.model.PatreonRawTier
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.model.StoryMetricHistory
import com.vinicius741.webnovelarchiver.domain.model.StoryMetricSnapshot
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Patreon figures belong to a creator, not a novel: several stories can link the same Patreon page.
 * Live library stories sharing a [creatorKey] share their latest [Story.patreonStats], and their
 * recorded Patreon history is charted as one series. Archived snapshots stay read-only.
 */
object PatreonCreatorPlanning {
    // Path prefixes placed before the creator slug (patreon.com/c/<slug>, /cw/<slug>, /join/<slug>).
    private val SLUG_PREFIXES = setOf("c", "cw", "join")

    // Paths that name a creator only through a ?u= user id (creators without a custom page name).
    private val USER_ID_PATHS = setOf("user", "profile")

    // Paths that do not name a creator by slug.
    private val NON_CREATOR_PATHS =
        USER_ID_PATHS +
            setOf(
                "posts",
                "home",
                "login",
                "signup",
                "checkout",
                "membership",
                "messages",
                "search",
                "collection",
                "explore",
                "settings",
                "notifications",
                "api",
            )

    private val USER_ID_QUERY = Regex("(?:^|&)u=(\\d+)(?:&|$)")
    private val SLUG = Regex("[\\p{L}\\p{N}_.-]+")

    /** Stable identity of the creator behind a Patreon URL, or null when the URL names no creator. */
    fun creatorKey(patreonUrl: String?): String? {
        userId(patreonUrl)?.let { return "u:$it" }
        return creatorSlug(patreonUrl)?.lowercase()
    }

    /** The numeric id of a `patreon.com/user?u=<id>` link, or null for any other link. */
    fun userId(patreonUrl: String?): String? {
        val uri = patreonUri(patreonUrl) ?: return null
        if (pathSegments(uri).firstOrNull()?.lowercase() !in USER_ID_PATHS) return null
        return USER_ID_QUERY.find(uri.query.orEmpty())?.groupValues?.get(1)
    }

    /**
     * The canonical link for what the user typed or pasted, or null when it isn't a link to a
     * creator's page (another site, a single post, the Patreon home page). The scheme is optional,
     * and sub-pages and tracking parameters are dropped.
     */
    fun normalizeCreatorLink(input: String): String? {
        val text = input.trim()
        val url = if ("://" in text) text else "https://$text"
        if (patreonUri(url)?.scheme?.lowercase() !in setOf("http", "https")) return null
        userId(url)?.let { return "https://www.patreon.com/user?u=$it" }
        return creatorSlug(url)?.takeIf(SLUG::matches)?.let { "https://www.patreon.com/$it" }
    }

    /**
     * A Patreon link scraped from a source page, with tracking parameters dropped. Creators without
     * a custom page name are linked only as `patreon.com/user?u=<id>`, so that id is kept; a
     * `/user` or `/profile` link without one names nobody and yields null.
     */
    fun sourceLink(url: String): String? {
        userId(url)?.let { return "https://www.patreon.com/user?u=$it" }
        val stripped = url.substringBefore('#').substringBefore('?')
        val first = patreonUri(stripped)?.let(::pathSegments)?.firstOrNull()?.lowercase()
        return stripped.takeUnless { first in USER_ID_PATHS }
    }

    /** Whether both links lead to one creator, so stats and early-access links stay valid across them. */
    fun sameCreator(
        first: String?,
        second: String?,
    ): Boolean = first == second || creatorKey(first)?.let { it == creatorKey(second) } == true

    private fun patreonUri(patreonUrl: String?): URI? {
        if (patreonUrl.isNullOrBlank()) return null
        val uri = runCatching { URI(patreonUrl.trim()) }.getOrNull() ?: return null
        return uri.takeIf { it.host?.lowercase()?.removePrefix("www.") == "patreon.com" }
    }

    private fun pathSegments(uri: URI): List<String> =
        uri.path
            .orEmpty()
            .split('/')
            .filter { it.isNotBlank() }

    private fun creatorSlug(patreonUrl: String?): String? {
        val segments = patreonUri(patreonUrl)?.let(::pathSegments) ?: return null
        val first = segments.firstOrNull() ?: return null
        val slug = if (first.lowercase() in SLUG_PREFIXES) segments.getOrNull(1) else first
        return slug?.takeUnless { it.lowercase() in NON_CREATOR_PATHS }
    }

    private fun sharesCreator(
        story: Story,
        key: String,
    ): Boolean = story.isArchived != true && creatorKey(story.effectivePatreonUrl) == key

    /** Other live stories that link the same Patreon creator as [story]. */
    fun siblings(
        story: Story,
        library: Collection<Story>,
    ): List<Story> {
        val key = creatorKey(story.effectivePatreonUrl) ?: return emptyList()
        return library.filter { it.id != story.id && sharesCreator(it, key) }
    }

    /**
     * Brings every live story sharing a creator up to that creator's freshest stats (by
     * [PatreonRawStats.capturedAt]). Mutates [library] in place and returns the ids it changed, so
     * startup can persist only those documents.
     */
    fun reconcileLibrary(library: List<Story>): Set<String> {
        val changed = mutableSetOf<String>()
        library
            .filter { it.isArchived != true }
            .groupBy { creatorKey(it.effectivePatreonUrl) }
            .forEach { (key, group) ->
                if (key == null || group.size < 2) return@forEach
                val freshest = group.mapNotNull { it.patreonStats }.maxByOrNull { it.capturedAt } ?: return@forEach
                group.filter { it.patreonStats != freshest }.forEach { story ->
                    story.patreonStats = freshest
                    changed += story.id
                }
            }
        return changed
    }

    /**
     * Combines the Patreon measurements recorded in several stories' histories (all for one
     * creator) into one history holding only Patreon snapshots. Each source's delta-encoded tier
     * ladders are resolved before the union, since a carried ladder only makes sense within its own
     * file; the result keeps explicit ladders and the latest measurement per calendar day, matching
     * [MetricSnapshotPlanning.appendAndRetain]'s coalescing.
     */
    fun mergedPatreonHistory(
        storyId: String,
        histories: List<StoryMetricHistory>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): StoryMetricHistory {
        val perDay = LinkedHashMap<LocalDate, StoryMetricSnapshot>()
        histories
            .flatMap(::resolvedPatreonSnapshots)
            .sortedBy { it.capturedAt }
            .forEach { snapshot ->
                perDay[Instant.ofEpochMilli(snapshot.capturedAt).atZone(zone).toLocalDate()] = snapshot
            }
        return StoryMetricHistory(storyId = storyId, snapshots = perDay.values.sortedBy { it.capturedAt }.toMutableList())
    }

    private fun resolvedPatreonSnapshots(history: StoryMetricHistory): List<StoryMetricSnapshot> {
        var carriedTiers: List<PatreonRawTier>? = null
        return history.snapshots.mapNotNull { snap ->
            val raw = snap.patreonRaw ?: return@mapNotNull null
            raw.tiers?.let { carriedTiers = it }
            StoryMetricSnapshot(capturedAt = snap.capturedAt, patreonRaw = raw.copy(tiers = raw.tiers ?: carriedTiers))
        }
    }
}
