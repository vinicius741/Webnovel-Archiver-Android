package com.vinicius741.webnovelarchiver.feature.library

import com.vinicius741.webnovelarchiver.data.repository.DownloadUiSnapshot
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.DisplayPreferences
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.model.Tab
import com.vinicius741.webnovelarchiver.domain.model.TtsStoryPosition
import com.vinicius741.webnovelarchiver.tts.TtsPlaybackSnapshot
import com.vinicius741.webnovelarchiver.tts.TtsPlaybackUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryScreenModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val fantasy = Tab(id = "t1", name = "Fantasy", order = 1)
    private val scifi = Tab(id = "t0", name = "Sci-fi", order = 0)

    private fun story(
        id: String,
        title: String = id,
        author: String = "Author",
        tabId: String? = null,
        downloaded: Int = 1,
        total: Int = 2,
    ) = Story(
        id = id,
        title = title,
        author = author,
        sourceUrl = "https://source.test/$id",
        tabId = tabId,
        totalChapters = total,
        downloadedChapters = downloaded,
        chapters =
            MutableList(
                total,
            ) { index -> Chapter(id = "$id-$index", title = "Chapter ${index + 1}", downloaded = index < downloaded) },
    )

    private val data =
        FakeLibraryData(
            listOf(story("a", "Alpha", tabId = "t1"), story("b", "Beta", tabId = "t0"), story("c", "Gamma")),
            listOf(fantasy, scifi),
        )

    private fun TestScope.model(restored: LibrarySavedState? = null): LibraryScreenModel =
        LibraryScreenModel(data, restored, scope = CoroutineScope(SupervisorJob() + dispatcher))

    /** Collects the model's state, the way an attached renderer does. */
    private fun TestScope.observe(model: LibraryScreenModel) {
        backgroundScope.launch(dispatcher) { model.state.collect {} }
        runCurrent()
    }

    @Test
    fun theStateCombinesTheLibraryTabsAndSavedPreferences() =
        runTest(dispatcher) {
            data.prefs =
                DisplayPreferences(
                    libraryTabId = "t1",
                    librarySortOption = "title",
                    librarySortAscending = true,
                    libraryViewMode = "covers",
                )

            val state = model().state.value

            assertSame("the published list is shared, not copied", data.downloads.value.library, state.stories)
            assertEquals(listOf("t0", "t1"), state.tabs.map(Tab::id))
            assertEquals(
                "Unassigned first, then tabs in order, then All",
                listOf(null, "t0", "t1", LibraryTabSelection.ALL_TAB_ID),
                state.pageTabs,
            )
            assertEquals("t1", state.filter.selectedTabId)
            assertEquals("title", state.filter.sortOption)
            assertTrue(state.filter.sortAscending)
            assertEquals(LibraryViewMode.COVERS, state.viewMode)
            assertFalse(state.selecting)
            assertFalse(state.searching)
        }

    @Test
    fun aDeletedSavedTabFallsBackToAllAndAnEmptyLibraryHasNoPages() =
        runTest(dispatcher) {
            data.prefs = DisplayPreferences(libraryTabId = "gone")
            assertEquals(
                LibraryTabSelection.ALL_TAB_ID,
                model()
                    .state.value.filter.selectedTabId,
            )

            data.downloads.value = DownloadUiSnapshot(libraryVersion = 9)
            val empty = model().state.value
            assertTrue(empty.pageTabs.isEmpty())
            assertTrue(empty.buildKey.empty)
        }

    @Test
    fun searchOpensTypesAndClosesAndBackClearsSearchAndTags() =
        runTest(dispatcher) {
            val model = model()
            val browsing = model.state.value.buildKey

            model.openSearch()
            assertTrue(model.state.value.searching)
            assertNotEquals("opening search rebuilds the header", browsing, model.state.value.buildKey)
            assertTrue(model.state.value.backClears)

            model.setQuery("alp")
            assertEquals("alp", model.state.value.filter.query)
            assertEquals(
                listOf("a"),
                model.state.value.filter
                    .applyTo(model.state.value.stories)
                    .map(Story::id),
            )
            val searching = model.state.value.buildKey
            model.setQuery("")
            assertEquals("clearing the field keeps the search bar", searching, model.state.value.buildKey)

            model.setQuery("x")
            model.setFilter(
                model.state.value.filter
                    .copy(selectedTags = setOf("Fantasy")),
            )
            model.clearSearchAndFilters()
            val cleared = model.state.value
            assertEquals("", cleared.filter.query)
            assertTrue(cleared.filter.selectedTags.isEmpty())
            assertFalse(cleared.searching)
            assertFalse(cleared.backClears)

            model.setQuery("q")
            model.closeSearch()
            assertEquals("", model.state.value.filter.query)
            assertFalse(model.state.value.searching)
        }

    @Test
    fun tagsApplyAtOnceAndOnlyASortChangeIsSaved() =
        runTest(dispatcher) {
            val model = model()

            model.setFilter(
                model.state.value.filter
                    .copy(selectedTags = setOf("x", "y")),
            )
            assertEquals(setOf("x", "y"), model.state.value.filter.selectedTags)
            assertTrue(model.state.value.backClears)
            runCurrent()
            assertEquals("tags are not preferences", 0, data.prefSaves)

            model.removeTag("x")
            assertEquals(setOf("y"), model.state.value.filter.selectedTags)
            model.clearTags()
            assertTrue(
                model.state.value.filter.selectedTags
                    .isEmpty(),
            )

            model.setFilter(
                model.state.value.filter
                    .copy(sortOption = "title", sortAscending = true),
            )
            assertEquals("the sort applies before the save lands", "title", model.state.value.filter.sortOption)
            runCurrent()
            assertEquals(1, data.prefSaves)
            assertEquals("title", data.prefs.librarySortOption)
            assertTrue(data.prefs.librarySortAscending)
        }

    @Test
    fun aPickedTabIsSavedAndUnassignedSurvivesARender() =
        runTest(dispatcher) {
            val model = model()

            model.selectTab("t0")
            assertEquals("t0", model.state.value.filter.selectedTabId)
            runCurrent()
            assertEquals("t0", data.prefs.libraryTabId)

            model.selectTab(null)
            runCurrent()
            // The saved preference cannot say Unassigned (it stores All), but this screen keeps it.
            assertEquals(LibraryTabSelection.ALL_TAB_ID, data.prefs.libraryTabId)
            assertNull(model.onRender().filter.selectedTabId)

            model.selectTab(null)
            runCurrent()
            assertEquals("picking the same tab again saves nothing", 2, data.prefSaves)
        }

    @Test
    fun aFailedSaveIsReportedAndTheSavedValueAppliesAgain() =
        runTest(dispatcher) {
            val model = model()
            val messages = mutableListOf<String>()
            backgroundScope.launch(dispatcher) { model.messages.collect { messages += it } }
            data.failSaves = true

            model.switchViewMode()
            assertEquals(LibraryViewMode.COVERS, model.state.value.viewMode)
            runCurrent()

            assertEquals(listOf("disk full"), messages)
            assertEquals(LibraryViewMode.LIST, model.state.value.viewMode)
        }

    @Test
    fun switchingTheLayoutRebuildsAndForgetsScrollOffsets() =
        runTest(dispatcher) {
            val model = model()
            model.rememberScroll("t1", 400)
            val before = model.state.value.buildKey

            model.switchViewMode()
            runCurrent()

            assertEquals(LibraryViewMode.COVERS, model.state.value.viewMode)
            assertEquals("covers", data.prefs.libraryViewMode)
            assertNotEquals(before, model.state.value.buildKey)
            assertEquals(0, model.scrollOffset("t1"))
        }

    @Test
    fun selectionStartsTogglesSelectsAllVisibleAndEndsWithTheLastNovel() =
        runTest(dispatcher) {
            val model = model()
            model.selectTab("t1")

            model.startSelection("a")
            assertTrue(model.state.value.selecting)
            assertEquals(true, model.state.value.selectionOf("a"))
            assertEquals(false, model.state.value.selectionOf("b"))
            assertFalse("selection hides the search", model.state.value.searching)

            model.toggleSelection("b")
            assertEquals(setOf("a", "b"), model.state.value.selectedStoryIds)
            val selecting = model.state.value.buildKey
            model.toggleSelection("b")
            assertEquals("toggling within selection mode does not rebuild", selecting, model.state.value.buildKey)

            // Select all covers the novels the current tab shows (only Alpha in Fantasy): all selected, so it clears them.
            model.toggleSelectAll()
            assertFalse(model.state.value.selecting)
            assertNull(model.state.value.selectionOf("a"))

            model.startSelection("a")
            model.toggleSelection("a")
            assertFalse("unselecting the last novel leaves selection mode", model.state.value.selecting)
        }

    @Test
    fun selectedNovelsThatLeaveTheLibraryAreDropped() =
        runTest(dispatcher) {
            val model = model()
            observe(model)
            model.startSelection("a")
            model.toggleSelection("c")

            data.publish(
                data.downloads.value.library
                    .filter { it.id != "a" },
            )
            runCurrent()

            assertEquals(setOf("c"), model.state.value.selectedStoryIds)
        }

    @Test
    fun theLibraryIsFollowedOnlyWhileObservedAndOnlyWhenItsVersionChanges() =
        runTest(dispatcher) {
            val model = model()
            val first = model.state.value.library

            data.publish(data.downloads.value.library + story("d"))
            runCurrent()
            assertSame("not observed: nothing is recomputed", first, model.state.value.library)

            observe(model)
            assertEquals(4, model.state.value.stories.size)
            val observed = model.state.value.library

            data.downloads.value =
                data.downloads.value.copy(
                    version = 99,
                    queueVersion = 5,
                    library =
                        data.downloads.value.library
                            .toList(),
                )
            runCurrent()
            assertSame("a queue-only publish keeps the library", observed, model.state.value.library)
        }

    @Test
    fun aRenderTakesTheLatestLibraryEvenWhenARestoreKeptItsVersion() =
        runTest(dispatcher) {
            val model = model()
            data.downloads.value = data.downloads.value.copy(library = listOf(story("z")))

            assertEquals(listOf("z"), model.onRender().stories.map(Story::id))
        }

    @Test
    fun downloadQueuesMissingChaptersAndLeavesSelectionOrSaysNothingIsMissing() =
        runTest(dispatcher) {
            val complete = story("full", downloaded = 2, total = 2)
            data.publish(data.downloads.value.library + complete)
            val model = model()

            model.startSelection("full")
            assertEquals("Every chapter is already downloaded", model.downloadSelected())
            assertTrue("nothing queued: selection stays", model.state.value.selecting)
            assertTrue(data.queued.isEmpty())

            model.toggleSelection("a")
            model.toggleSelection("b")
            assertEquals("Queued missing chapters for 2 novels", model.downloadSelected())
            assertEquals(mapOf("a" to listOf(1), "b" to listOf(1)), data.queued)
            assertFalse(model.state.value.selecting)
        }

    @Test
    fun deleteAsksWithTheRemovedChapterWarningThenDeletesAndLeavesSelection() =
        runTest(dispatcher) {
            val model = model()
            assertNull(model.deletePrompt())
            data.stories
                .getValue("a")
                .chapters[0]
                .removedFromSourceAt = 1L

            model.startSelection("a")
            model.toggleSelection("b")
            assertEquals(LibrarySelectionPlanning.deletePrompt(2, 1), model.deletePrompt())

            model.deleteSelected()
            runCurrent()

            assertEquals(listOf("a", "b"), data.deleted)
            assertFalse(model.state.value.selecting)
            assertEquals(
                listOf("c"),
                model.state.value.stories
                    .map(Story::id),
            )
        }

    @Test
    fun moveSavesEachNovelToTheTabAndLeavesSelection() =
        runTest(dispatcher) {
            val model = model()
            model.startSelection("a")
            model.toggleSelection("c")

            model.moveSelected("t0")
            runCurrent()

            assertEquals("t0", data.stories.getValue("a").tabId)
            assertEquals("t0", data.stories.getValue("c").tabId)
            assertFalse(model.state.value.selecting)
            assertEquals("no novel is unassigned any more", listOf("t0", "t1", LibraryTabSelection.ALL_TAB_ID), model.state.value.pageTabs)
        }

    @Test
    fun continueListeningIsLookedUpOnRenderAndHidesWhilePlayingOrOnceDismissed() =
        runTest(dispatcher) {
            data.positions = listOf(TtsStoryPosition(storyId = "a", chapterId = "a-0", updatedAt = 500L))
            val model = model()
            observe(model)

            model.onRender()
            runCurrent()
            val card = model.state.value.continueListening
            assertEquals("a-0", card?.target?.chapter?.id)
            assertEquals(500L, card?.latestPositionAt)

            model.playContinueListening()
            assertEquals(listOf("a" to "a-0"), data.played)
            data.startPlayback()
            assertNull("the card goes away once playback starts", model.state.value.continueListening)

            data.playback.value = TtsPlaybackUpdate(snapshot = null, isAuthoritative = true)
            runCurrent()
            assertNull("after a stop it waits for the next render", model.state.value.continueListening)

            model.onRender()
            runCurrent()
            model.dismissContinueListening()
            assertNull(model.state.value.continueListening)
            runCurrent()
            assertEquals(500L, data.prefs.continueListeningDismissedThrough)
            model.onRender()
            runCurrent()
            assertNull("a dismissal lasts until something newer is heard", model.state.value.continueListening)
        }

    @Test
    fun continueListeningIsNotLookedUpWhileSelectingOrSearchingOrPlaying() =
        runTest(dispatcher) {
            data.positions = listOf(TtsStoryPosition(storyId = "a", chapterId = "a-0", updatedAt = 500L))
            val model = model()
            model.openSearch()
            model.onRender()
            runCurrent()
            assertEquals(0, data.positionReads)

            model.closeSearch()
            data.playback.value = TtsPlaybackUpdate(snapshot = playing(), isAuthoritative = true)
            model.onRender()
            runCurrent()
            assertEquals(0, data.positionReads)
        }

    @Test
    fun savedStateRoundTripsQueryTagsAndScrollButNotSearchOrSelection() =
        runTest(dispatcher) {
            val model = model()
            model.setQuery("gam")
            model.setFilter(
                model.state.value.filter
                    .copy(selectedTags = setOf("Fantasy")),
            )
            model.rememberScroll("t1", 120)
            model.rememberScroll(LibraryTabSelection.UNASSIGNED_TAB_ID, 30)
            model.startSelection("a")

            val saved = model.savedState()
            assertEquals(LibrarySavedState("gam", setOf("Fantasy"), mapOf("t1" to 120, "unassigned" to 30)), saved)

            val restored = model(saved)
            val state = restored.state.value
            assertEquals("gam", state.filter.query)
            assertEquals(setOf("Fantasy"), state.filter.selectedTags)
            assertFalse(state.selecting)
            assertTrue("a remembered query reopens the search bar", state.searching)
            assertEquals(120, restored.scrollOffset("t1"))
            assertEquals(30, restored.scrollOffset("unassigned"))
        }

    private fun playing() =
        TtsPlaybackSnapshot(
            title = "Chapter",
            storyId = "a",
            chapterId = "a-0",
            chunkIndex = 0,
            totalChunks = 3,
            isPlaying = true,
            isPaused = false,
        )

    private fun FakeLibraryData.startPlayback() {
        playback.value = TtsPlaybackUpdate(snapshot = playing(), isAuthoritative = true)
        dispatcher.scheduler.runCurrent()
    }
}

/** In-memory [LibraryData]: a published library that publishes on every change, like the repository. */
internal class FakeLibraryData(
    initial: List<Story>,
    private val tabList: List<Tab>,
) : LibraryData {
    override val downloads = MutableStateFlow(DownloadUiSnapshot(libraryVersion = 1, library = initial))
    override val playback = MutableStateFlow(TtsPlaybackUpdate(snapshot = null, isAuthoritative = false))
    val stories: Map<String, Story> get() = downloads.value.library.associateBy(Story::id)
    var prefs = DisplayPreferences()
    var prefSaves = 0
    var failSaves = false
    var positions: List<TtsStoryPosition> = emptyList()
    var positionReads = 0
    val queued = mutableMapOf<String, List<Int>>()
    val deleted = mutableListOf<String>()
    val played = mutableListOf<Pair<String, String>>()

    fun publish(library: List<Story>) {
        downloads.value = downloads.value.copy(libraryVersion = downloads.value.libraryVersion + 1, library = library)
    }

    override fun tabs(): List<Tab> = tabList

    override fun displayPreferences(): DisplayPreferences = prefs.copy()

    override suspend fun updateDisplayPreferences(block: (DisplayPreferences) -> DisplayPreferences) {
        if (failSaves) error("disk full")
        prefSaves += 1
        prefs = block(prefs.copy())
    }

    // The fake hands out its own instances; the tests read them back through [stories].
    override fun story(id: String): Story? = stories[id]

    override suspend fun saveStory(story: Story) = publish(downloads.value.library.map { if (it.id == story.id) story else it })

    override suspend fun deleteStory(id: String) {
        deleted += id
        publish(downloads.value.library.filter { it.id != id })
    }

    override suspend fun listeningPositions(): List<TtsStoryPosition> {
        positionReads += 1
        return positions
    }

    override fun queueDownload(
        story: Story,
        indexes: List<Int>,
    ) {
        queued[story.id] = indexes
    }

    override fun playIfGenerated(
        storyId: String,
        chapterId: String,
        onStart: () -> Unit,
    ) {
        played += storyId to chapterId
    }
}
