package com.vinicius741.webnovelarchiver.feature.library

import com.vinicius741.webnovelarchiver.domain.model.Tab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddStoryScreenModelTest {
    private var tabs = listOf(Tab(id = "b", name = "Later", order = 1), Tab(id = "a", name = "First", order = 0))
    private var libraryTab: String? = "b"

    private fun model() = AddStoryScreenModel({ tabs }, { libraryTab }, CoroutineScope(Job()))

    @Test
    fun newStoriesGoToTheLibrarysTabElseTheFirstTabUntilOneIsPicked() {
        val model = model()
        assertEquals(
            listOf("a", "b"),
            model.state.value.tabs
                .map(Tab::id),
        )
        assertEquals("b", model.state.value.tabId)

        libraryTab = "__all__"
        assertEquals("All is not a real tab: the first one", "a", model.refresh().tabId)

        model.chooseTab(null)
        libraryTab = "b"
        assertNull("a pick (Unassigned) sticks across renders", model.refresh().tabId)

        tabs = emptyList()
        assertNull(model().state.value.tabId)
    }

    @Test
    fun theDraftAndTheFetchStatusSurviveRenders() {
        val model = model()
        model.setUrl("https://www.royalroad.com/fiction/1")
        assertFalse(model.state.value.fetching)

        model.fetchStatus("Starting...")
        model.fetchStatus("Fetching chapters")
        val fetching = model.refresh()
        assertTrue(fetching.fetching)
        assertEquals("Fetching chapters", fetching.status)
        assertEquals("https://www.royalroad.com/fiction/1", fetching.url)

        model.fetchFailed()
        assertFalse(model.state.value.fetching)
        assertEquals("the draft stays for another try", "https://www.royalroad.com/fiction/1", model.state.value.url)
    }

    @Test
    fun aNewModelStartsEmpty() {
        model().setUrl("https://example.test")

        assertEquals("", model().state.value.url)
    }
}
