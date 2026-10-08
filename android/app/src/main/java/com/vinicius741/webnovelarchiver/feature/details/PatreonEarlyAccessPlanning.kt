package com.vinicius741.webnovelarchiver.feature.details

import com.vinicius741.webnovelarchiver.domain.model.PatreonEarlyAccessLink
import com.vinicius741.webnovelarchiver.source.PatreonCollection

/** Display rules for the Details screen's Patreon early-access row and collection picker. */
object PatreonEarlyAccessPlanning {
    private val words = Regex("""[^\p{L}\p{N}]+""")
    private val ignoredWords = setOf("the", "a", "an", "of", "and", "as", "to", "in", "on", "book", "books")

    fun summary(
        link: PatreonEarlyAccessLink?,
        copyCount: Int,
    ): String =
        when {
            link == null -> "Not linked · tap to choose a Patreon collection"
            link.lastError != null -> "${link.collectionTitle} · ${link.lastError}"
            link.lastCheckedAt == null -> "${link.collectionTitle} · checks on next sync"
            copyCount == 0 -> "${link.collectionTitle} · no chapters ahead of the public release"
            else -> "${link.collectionTitle} · $copyCount ${if (copyCount == 1) "chapter" else "chapters"} ahead"
        }

    /** Collections sharing the most title words with the novel come first; ties keep Patreon's order. */
    fun rankCollections(
        storyTitle: String,
        collections: List<PatreonCollection>,
    ): List<PatreonCollection> {
        val storyWords = significantWords(storyTitle)
        return collections
            .withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<PatreonCollection>> { (_, collection) ->
                    overlap(storyWords, collection.title)
                }.thenBy { it.index },
            ).map { it.value }
    }

    fun optionLabel(collection: PatreonCollection): String =
        collection.postCount?.let { "${collection.title} ($it ${if (it == 1) "post" else "posts"})" } ?: collection.title

    private fun overlap(
        storyWords: Set<String>,
        title: String,
    ): Int = significantWords(title).count { it in storyWords }

    private fun significantWords(text: String): Set<String> =
        text
            .lowercase()
            .split(words)
            .filter { it.isNotBlank() && it !in ignoredWords }
            .toSet()
}
