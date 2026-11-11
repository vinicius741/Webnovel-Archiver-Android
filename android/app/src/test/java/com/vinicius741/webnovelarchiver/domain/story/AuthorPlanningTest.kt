package com.vinicius741.webnovelarchiver.domain.story

import com.vinicius741.webnovelarchiver.domain.model.Story
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthorPlanningTest {
    @Test
    fun authorKeyIgnoresCaseSpacingAndByPrefix() {
        assertEquals("jane doe", AuthorPlanning.authorKey("  Jane   Doe "))
        assertEquals("jane doe", AuthorPlanning.authorKey("by Jane Doe"))
        assertEquals("jane doe", AuthorPlanning.authorKey("JANE DOE"))
    }

    @Test
    fun authorKeyRejectsBlankAndPlaceholderAuthors() {
        assertNull(AuthorPlanning.authorKey(null))
        assertNull(AuthorPlanning.authorKey("   "))
        assertNull(AuthorPlanning.authorKey("Unknown Author"))
        assertNull(AuthorPlanning.authorKey("unknown"))
        assertFalse(AuthorPlanning.hasKnownAuthor(story("a", "A", "Unknown Author")))
        assertTrue(AuthorPlanning.hasKnownAuthor(story("a", "A", "Jane")))
    }

    @Test
    fun otherNovelsMatchesNormalizedAuthorExcludesSelfAndSortsByTitle() {
        val current = story("1", "Current", "Jane Doe")
        val library =
            listOf(
                current,
                story("2", "zeta", "jane doe"),
                story("3", "Alpha", "by Jane  Doe"),
                story("4", "Beta", "John Roe"),
            )

        val others = AuthorPlanning.otherNovels(current, library)

        assertEquals(listOf("3", "2"), others.map(Story::id))
    }

    @Test
    fun otherNovelsIsEmptyForPlaceholderAuthor() {
        val current = story("1", "Current", "Unknown Author")
        val library = listOf(current, story("2", "Other", "Unknown Author"))

        assertTrue(AuthorPlanning.otherNovels(current, library).isEmpty())
    }

    private fun story(
        id: String,
        title: String,
        author: String,
    ) = Story(id = id, title = title, author = author)
}
