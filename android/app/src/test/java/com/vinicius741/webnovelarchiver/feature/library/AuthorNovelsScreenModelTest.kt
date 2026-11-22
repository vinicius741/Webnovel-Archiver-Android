package com.vinicius741.webnovelarchiver.feature.library

import com.vinicius741.webnovelarchiver.domain.model.Story
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthorNovelsScreenModelTest {
    private var library =
        listOf(
            Story(id = "1", title = "Zeta", author = " Ann Lee "),
            Story(id = "2", title = "beta", author = "ann lee"),
            Story(id = "3", title = "Alpha", author = "Ann Lee"),
            Story(id = "4", title = "Other", author = "Bob"),
        )

    private fun model(storyId: String) = AuthorNovelsScreenModel(storyId, { library }, CoroutineScope(Job()))

    @Test
    fun listsTheAuthorsOtherNovelsByTitleFromTheSharedLibrary() {
        val state = model("1").state.value

        assertSame(library[0], state.story)
        assertEquals("Ann Lee", state.author)
        assertEquals(listOf("3", "2"), state.others.map(Story::id))
    }

    @Test
    fun aStoryThatLeftTheLibraryHasNoPage() {
        val model = model("4")
        assertTrue(
            model.state.value.others
                .isEmpty(),
        )

        library = library.filter { it.id != "4" }

        assertNull(model.refresh().story)
    }

    @Test
    fun aBlankAuthorShowsAsUnknown() {
        library = listOf(Story(id = "x", author = "  "))

        assertEquals("Unknown author", model("x").state.value.author)
    }
}
