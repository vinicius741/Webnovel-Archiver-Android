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

    // Paths that do not name a creator by slug; without the stripped ?u= id they cannot be matched.
    private val NON_CREATOR_PATHS = setOf("user", "posts", "home", "login", "checkout", "membership", "messages", "search")

    /** Stable identity of the creator behind a Patreon URL, or null when the URL names no creator slug. */
    fun creatorKey(patreonUrl: String?): String? {
        if (patreonUrl.isNullOrBlank()) return null
        val uri = runCatching { URI(patreonUrl.trim()) }.getOrNull() ?: return null
        if (uri.host?.lowercase()?.removePrefix("www.") != "patreon.com") return null
        val segments =
            uri.path
                .orEmpty()
                .split('/')
                .filter { it.isNotBlank() }
        val first = segments.firstOrNull()?.lowercase() ?: return null
        val slug = if (first in SLUG_PREFIXES) segments.getOrNull(1)?.lowercase() else first
        return slug?.takeUnless { it in NON_CREATOR_PATHS }
    }

    private fun sharesCreator(
        story: Story,
        key: String,
    ): Boolean = story.isArchived != true && creatorKey(story.patreonUrl) == key

    /** Other live stories that link the same Patreon creator as [story]. */
    fun siblings(
        story: Story,
        library: Collection<Story>,
    ): List<Story> {
        val key = creatorKey(story.patreonUrl) ?: return emptyList()
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
            .groupBy { creatorKey(it.patreonUrl) }
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
