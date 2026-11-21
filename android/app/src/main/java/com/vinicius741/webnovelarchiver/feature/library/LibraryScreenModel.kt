package com.vinicius741.webnovelarchiver.feature.library

import com.vinicius741.webnovelarchiver.data.repository.DownloadUiSnapshot
import com.vinicius741.webnovelarchiver.domain.model.DisplayPreferences
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.model.Tab
import com.vinicius741.webnovelarchiver.domain.model.TtsStoryPosition
import com.vinicius741.webnovelarchiver.domain.story.StoryActionGuards
import com.vinicius741.webnovelarchiver.domain.story.UpdatesPlanning
import com.vinicius741.webnovelarchiver.navigation.ScreenModel
import com.vinicius741.webnovelarchiver.tts.TtsContinueListening
import com.vinicius741.webnovelarchiver.tts.TtsPlaybackUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/** What the Library reads and changes: the published library, tabs, display preferences, listening places, and story actions. */
internal interface LibraryData {
    /** The repository's published library and queue; its `library` list is shared, never copied. */
    val downloads: StateFlow<DownloadUiSnapshot>
    val playback: StateFlow<TtsPlaybackUpdate>

    fun tabs(): List<Tab>

    fun displayPreferences(): DisplayPreferences

    suspend fun updateDisplayPreferences(block: (DisplayPreferences) -> DisplayPreferences)

    /** A copy of the story the caller may change. */
    fun story(id: String): Story?

    suspend fun saveStory(story: Story)

    /** Deletes the story and its generated audio. */
    suspend fun deleteStory(id: String)

    suspend fun listeningPositions(): List<TtsStoryPosition>

    /** Queues [indexes] of [story] for download (the download service and its permission prompt). */
    fun queueDownload(
        story: Story,
        indexes: List<Int>,
    )

    /** Plays the chapter from its generated audio, generating it first if needed; [onStart] runs as it starts. */
    fun playIfGenerated(
        storyId: String,
        chapterId: String,
        onStart: () -> Unit,
    )
}

/**
 * One published library: [stories] is the repository's own list, so equality is identity and a
 * state change never compares (or copies) ~240 stories.
 */
internal class LibraryStories(
    val version: Long,
    val stories: List<Story>,
)

/** The Continue listening card: the place to resume, and the newest saved place a dismissal covers. */
internal data class LibraryContinue(
    val target: TtsContinueListening.Target,
    val latestPositionAt: Long,
)

/** What a Library build depends on; a different key rebuilds the screen, anything else is patched. */
internal data class LibraryBuildKey(
    val selecting: Boolean,
    val searching: Boolean,
    val viewMode: LibraryViewMode,
    val empty: Boolean,
    val pageTabs: List<String?>,
)

internal data class LibraryUiState(
    val library: LibraryStories,
    /** Tabs in display order. */
    val tabs: List<Tab>,
    /** Pager pages: Unassigned (null) when any novel has no tab, then each tab, then All; empty for an empty library. */
    val pageTabs: List<String?>,
    val filter: LibraryFilterState,
    /** The app-bar search field is open (it also shows whenever a query is remembered). */
    val searchOpen: Boolean,
    val viewMode: LibraryViewMode,
    /** Multi-select; non-empty means selection mode. */
    val selectedStoryIds: Set<String>,
    /** The Continue listening card, or null when it is hidden. */
    val continueListening: LibraryContinue?,
) {
    val stories: List<Story> get() = library.stories
    val selecting: Boolean get() = selectedStoryIds.isNotEmpty()
    val searching: Boolean get() = !selecting && (searchOpen || filter.query.isNotEmpty())

    /** System Back closes the search and clears the filters before it leaves the app. */
    val backClears: Boolean get() = searchOpen || filter.query.isNotEmpty() || filter.selectedTags.isNotEmpty()

    val buildKey: LibraryBuildKey get() = LibraryBuildKey(selecting, searching, viewMode, stories.isEmpty(), pageTabs)

    /** The selection state a card shows: null while browsing, else whether [storyId] is selected. */
    fun selectionOf(storyId: String): Boolean? = if (selecting) storyId in selectedStoryIds else null
}

/** The Library's view memory kept across a process death (see [LibraryScreenStatePersistence]). */
internal data class LibrarySavedState(
    val query: String = "",
    val selectedTags: Set<String> = emptySet(),
    /** Each tab's scroll offset, by [LibraryTabSelection.memoryKey]. */
    val tabScroll: Map<String, Int> = emptyMap(),
)

/**
 * The Library (`AppRoute.Library`, the root, so it lives as long as the activity): the published
 * library combined with the tabs, the display preferences (tab, sort, layout), the search and tag
 * filters, multi-select, and the Continue listening card. It follows the library and playback
 * while a renderer collects [state]; failed saves come out of [messages].
 */
internal class LibraryScreenModel(
    private val data: LibraryData,
    restored: LibrarySavedState? = null,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : ScreenModel(scope) {
    private var query = restored?.query.orEmpty()
    private var selectedTags = restored?.selectedTags.orEmpty()
    private var searchOpen = false
    private var selectedIds: Set<String> = emptySet()

    /** The tab picked on this screen (null is Unassigned); before any pick, the saved one applies. */
    private var tabChoice: String? = null
    private var tabChosen = false

    /** Preferences as this screen changed them, until every save has landed. */
    private var pendingPrefs: DisplayPreferences? = null
    private var savesInFlight = 0

    private var library = data.downloads.value.let { LibraryStories(it.libraryVersion, it.library) }
    private var continueCard: LibraryContinue? = null
    private val tabScroll = restored?.tabScroll.orEmpty().toMutableMap()
    private val messageChannel = Channel<String>(MESSAGE_BUFFER, BufferOverflow.DROP_OLDEST)
    private val mutableState = MutableStateFlow(compute())

    val state: StateFlow<LibraryUiState> = mutableState.asStateFlow()

    /** One-off messages for the user (failed saves), buffered until a renderer shows them. */
    val messages: Flow<String> = messageChannel.receiveAsFlow()

    init {
        this.scope.launch {
            mutableState.subscriptionCount
                .map { it > 0 }
                .distinctUntilChanged()
                .collectLatest { observed ->
                    if (observed) merge(data.downloads, data.playback).collect { refresh() }
                }
        }
    }

    /**
     * Brings the state up to date for a new render: the latest library (also after a restore that
     * kept its version), tabs and preferences, and looks the Continue listening card up again.
     */
    fun onRender(): LibraryUiState {
        library = data.downloads.value.let { LibraryStories(it.libraryVersion, it.library) }
        val state = refresh()
        if (!state.selecting && !state.searching) lookUpContinueListening()
        return state
    }

    // Search and filters

    fun openSearch() = update { searchOpen = true }

    /** Closes the search bar, dropping its query. */
    fun closeSearch() =
        update {
            searchOpen = false
            query = ""
        }

    /** Typing in the search field; the field stays open even when it is cleared. */
    fun setQuery(text: String) =
        update {
            query = text
            searchOpen = true
        }

    /** What system Back clears: the search and the tag filters. */
    fun clearSearchAndFilters() =
        update {
            searchOpen = false
            query = ""
            selectedTags = emptySet()
        }

    /** A change from the Sort & filter sheet: tags apply at once, and a new sort is saved. */
    fun setFilter(next: LibraryFilterState) {
        val current = state.value.filter
        selectedTags = next.selectedTags
        if (next.sortOption != current.sortOption || next.sortAscending != current.sortAscending) {
            savePreferences("save library sort") { latest ->
                if (latest.librarySortOption == next.sortOption && latest.librarySortAscending == next.sortAscending) {
                    latest
                } else {
                    latest.copy(librarySortOption = next.sortOption, librarySortAscending = next.sortAscending)
                }
            }
        } else {
            refresh()
        }
    }

    fun removeTag(tag: String) = update { selectedTags = selectedTags - tag }

    fun clearTags() = update { selectedTags = emptySet() }

    fun selectTab(tabId: String?) {
        if (tabChosen && tabChoice == tabId) return
        tabChoice = tabId
        tabChosen = true
        val encoded = LibraryTabSelection.encode(tabId)
        savePreferences("save library tab") { latest ->
            if (latest.libraryTabId ==
                encoded
            ) {
                latest
            } else {
                latest.copy(libraryTabId = encoded)
            }
        }
    }

    /**
     * Switches between list cards and covers. Scroll offsets are pixel distances in the old layout's
     * geometry, so every tab starts at the top of the new one.
     */
    fun switchViewMode() {
        val next = state.value.viewMode.toggled
        tabScroll.clear()
        savePreferences("save library view") { it.copy(libraryViewMode = next.stored) }
    }

    // Selection

    fun startSelection(storyId: String) = update { selectedIds = setOf(storyId) }

    /** Toggles one novel; unselecting the last one leaves selection mode. */
    fun toggleSelection(storyId: String) = update { selectedIds = LibrarySelectionPlanning.toggle(selectedIds, storyId) }

    /** Select all / deselect all of the novels the current tab, search and filters show. */
    fun toggleSelectAll() {
        val current = state.value
        val visible = current.filter.applyTo(current.stories).map(Story::id)
        update { selectedIds = LibrarySelectionPlanning.toggleAll(selectedIds, visible) }
    }

    fun exitSelection() = update { selectedIds = emptySet() }

    /**
     * Queues every chapter not downloaded yet for each selected novel and leaves selection mode;
     * returns the message to show. When nothing is missing, selection stays open.
     */
    fun downloadSelected(): String {
        val stories = selectedIds.mapNotNull(data::story).filter(StoryActionGuards::canQueueDownloads)
        val withMissing = stories.filter { UpdatesPlanning.missingChapters(it) > 0 }
        if (withMissing.isEmpty()) {
            return if (stories.size == 1) "Every chapter is already downloaded" else "Every chapter of these novels is already downloaded"
        }
        withMissing.forEach { data.queueDownload(it, UpdatesPlanning.missingChapterIndexes(it)) }
        exitSelection()
        return LibrarySelectionPlanning.queuedMessage(withMissing.size)
    }

    /** The confirmation for deleting the selection, or null when nothing is selected. */
    fun deletePrompt(): String? {
        if (selectedIds.isEmpty()) return null
        val removedFromSource = selectedIds.sumOf { id -> data.story(id)?.chapters?.count { it.isRemovedFromSource } ?: 0 }
        return LibrarySelectionPlanning.deletePrompt(selectedIds.size, removedFromSource)
    }

    /** Deletes the selected novels and their audio, then leaves selection mode. */
    fun deleteSelected() {
        val ids = selectedIds.toList()
        if (ids.isEmpty()) return
        scope.launch {
            runCatching { ids.forEach { data.deleteStory(it) } }.onFailure { report("delete novels", it) }
            exitSelection()
        }
    }

    /** Moves the selected novels to [tabId] (null is Unassigned), then leaves selection mode. */
    fun moveSelected(tabId: String?) {
        val ids = selectedIds.toList()
        if (ids.isEmpty()) return
        scope.launch {
            runCatching {
                ids.forEach { id ->
                    data.story(id)?.let { story ->
                        story.tabId = tabId
                        data.saveStory(story)
                    }
                }
            }.onFailure { report("move novels", it) }
            exitSelection()
        }
    }

    // Continue listening

    /** Plays the card's chapter; the card goes away once playback starts. */
    fun playContinueListening() {
        val card = continueCard ?: return
        data.playIfGenerated(card.target.story.id, card.target.chapter.id) { hideContinueListening() }
    }

    /** Hides the card until something newer is heard, across restarts. */
    fun dismissContinueListening() {
        val card = continueCard ?: return
        continueCard = null
        savePreferences("dismiss continue listening") { it.copy(continueListeningDismissedThrough = card.latestPositionAt) }
    }

    // View memory

    fun rememberScroll(
        tabKey: String,
        offset: Int,
    ) {
        tabScroll[tabKey] = offset
    }

    fun scrollOffset(tabKey: String): Int = tabScroll[tabKey] ?: 0

    fun savedState(): LibrarySavedState = LibrarySavedState(query, selectedTags, tabScroll.toMap())

    /** Recomputes the state from its sources now and returns it. */
    fun refresh(): LibraryUiState {
        val snapshot = data.downloads.value
        if (snapshot.libraryVersion != library.version) library = LibraryStories(snapshot.libraryVersion, snapshot.library)
        return compute().also { mutableState.value = it }
    }

    private inline fun update(change: () -> Unit) {
        change()
        refresh()
    }

    private fun preferences(): DisplayPreferences = pendingPrefs ?: data.displayPreferences()

    /** Applies [change] to the state at once and saves it; a failed save is reported, and the saved value applies again. */
    private fun savePreferences(
        operation: String,
        change: (DisplayPreferences) -> DisplayPreferences,
    ) {
        pendingPrefs = change(preferences())
        savesInFlight += 1
        refresh()
        scope.launch {
            runCatching { data.updateDisplayPreferences(change) }.onFailure { report(operation, it) }
            savesInFlight -= 1
            if (savesInFlight == 0) {
                pendingPrefs = null
                refresh()
            }
        }
    }

    private fun lookUpContinueListening() {
        if (data.playback.value.snapshot != null) return
        scope.launch {
            val positions = runCatching { data.listeningPositions() }.getOrDefault(emptyList())
            val dismissedThrough = preferences().continueListeningDismissedThrough
            val target = TtsContinueListening.target(positions, dismissedThrough, data::story)
            continueCard = target?.let { LibraryContinue(it, positions.maxOf(TtsStoryPosition::updatedAt)) }
            refresh()
        }
    }

    private fun hideContinueListening() {
        continueCard = null
        refresh()
    }

    private fun report(
        operation: String,
        error: Throwable,
    ) {
        Timber.w(error, "Library operation failed: %s", operation)
        messageChannel.trySend(error.message ?: "Operation failed")
    }

    private fun compute(): LibraryUiState {
        val prefs = preferences()
        val tabs = data.tabs().sortedBy { it.order }
        val stories = library.stories
        val hasUnassigned = stories.any { it.tabId == null }
        if (selectedIds.isNotEmpty()) selectedIds = LibrarySelectionPlanning.prune(selectedIds, stories.map(Story::id))
        // A pick on this screen keeps Unassigned, which the saved preference cannot express.
        val stored = if (tabChosen) tabChoice ?: LibraryTabSelection.UNASSIGNED_TAB_ID else prefs.libraryTabId
        val filter =
            LibraryFilterState(
                query = query,
                selectedTabId = LibraryTabSelection.resolve(stored, tabs, hasUnassigned),
                selectedTags = selectedTags,
                sortOption = prefs.librarySortOption.ifBlank { "lastUpdated" },
                sortAscending = prefs.librarySortAscending,
            )
        val pageTabs =
            if (stories.isEmpty()) {
                emptyList()
            } else {
                buildList {
                    if (hasUnassigned) add(null)
                    addAll(tabs.map { it.id })
                    add(LibraryTabSelection.ALL_TAB_ID)
                }
            }
        // The mini player shows instead while something plays; after a stop the card is looked up again on the next render.
        if (data.playback.value.snapshot != null) continueCard = null
        return LibraryUiState(
            library = library,
            tabs = tabs,
            pageTabs = pageTabs,
            filter = filter,
            searchOpen = searchOpen,
            viewMode = LibraryViewMode.fromStored(prefs.libraryViewMode),
            selectedStoryIds = selectedIds,
            continueListening = continueCard,
        )
    }

    private companion object {
        const val MESSAGE_BUFFER = 8
    }
}
