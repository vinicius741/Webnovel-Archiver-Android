package com.vinicius741.webnovelarchiver.domain.story

import com.vinicius741.webnovelarchiver.domain.model.Story
import java.util.Locale

/**
 * Groups library novels by author. Sources format the same name slightly differently (casing,
 * spacing, a leading "by"), so matching compares a normalized [authorKey] rather than raw text.
 */
object AuthorPlanning {
    // Placeholders stored when a source exposed no author; they must not group unrelated novels.
    private val PLACEHOLDER_KEYS = setOf("unknown", "unknown author", "anonymous", "n/a")
    private val WHITESPACE = Regex("\\s+")
    private val BY_PREFIX = Regex("^by\\s+", RegexOption.IGNORE_CASE)

    /** Comparable author identity, or null when [author] is blank or a placeholder. */
    fun authorKey(author: String?): String? {
        val key =
            author
                .orEmpty()
                .replace(WHITESPACE, " ")
                .trim()
                .replace(BY_PREFIX, "")
                .lowercase(Locale.ROOT)
        return key.takeIf { it.isNotEmpty() && it !in PLACEHOLDER_KEYS }
    }

    /** True when [story] names a real author that other novels can be matched against. */
    fun hasKnownAuthor(story: Story): Boolean = authorKey(story.author) != null

    /** Library novels by [story]'s author, excluding [story] itself, ordered by title. */
    fun otherNovels(
        story: Story,
        library: Collection<Story>,
    ): List<Story> {
        val key = authorKey(story.author) ?: return emptyList()
        return library
            .filter { it.id != story.id && authorKey(it.author) == key }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, Story::title).thenBy(Story::id))
    }
}
