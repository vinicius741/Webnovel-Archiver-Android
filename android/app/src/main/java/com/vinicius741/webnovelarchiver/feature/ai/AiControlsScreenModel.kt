package com.vinicius741.webnovelarchiver.feature.ai

import com.vinicius741.webnovelarchiver.ai.AiCoverDraft
import com.vinicius741.webnovelarchiver.ai.AiCoverJobKind
import com.vinicius741.webnovelarchiver.ai.AiCoverJobState
import com.vinicius741.webnovelarchiver.ai.AiCoverPlanning
import com.vinicius741.webnovelarchiver.ai.AiDescriptionJobCoordinator
import com.vinicius741.webnovelarchiver.ai.CharacterJobState
import com.vinicius741.webnovelarchiver.data.storage.AiCoverVersion
import com.vinicius741.webnovelarchiver.domain.model.AiSettings
import com.vinicius741.webnovelarchiver.domain.model.CharacterPortrait
import com.vinicius741.webnovelarchiver.domain.model.CharacterRole
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.model.StoryCharacter
import com.vinicius741.webnovelarchiver.domain.model.StoryPhase
import com.vinicius741.webnovelarchiver.navigation.ScreenModel
import com.vinicius741.webnovelarchiver.operations.StoryOperationKind
import com.vinicius741.webnovelarchiver.operations.StoryOperationState
import com.vinicius741.webnovelarchiver.operations.StoryOperations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File

/** The three halves of the AI tab; the model remembers the one last shown while the story stays in the history. */
internal enum class AiControlsTab(
    val label: String,
) {
    COVER("Cover"),
    CHARACTERS("Characters"),
    DESCRIPTION("Description"),
}

/** What the Characters tab shows: the list, thumbnails, and the open character's portraits. */
internal data class CharactersView(
    val openId: String?,
    val roster: List<StoryCharacter>?,
    val thumbs: Map<String, File>,
    val portraits: List<CharacterPortrait>,
)

/** The Cover tab's strip: saved AI versions newest first, and which of them is the waiting new cover. */
internal data class CoverVersions(
    val versions: List<AiCoverVersion>,
    val newId: String?,
)

/** The cost lines of the latest description, cover prompt and cover image runs. */
internal data class AiCostLines(
    val description: String? = null,
    val coverPrompt: String? = null,
    val coverImage: String? = null,
)

/** Cover work saved on the device: a prompt alone, or a painted cover waiting for a decision. */
internal sealed interface SavedCoverWork {
    data class Prompt(
        val prompt: String,
    ) : SavedCoverWork

    data class Image(
        val draft: AiCoverDraft,
    ) : SavedCoverWork
}

/** Everything the AI tab draws. [story] is the model's own copy (a mutable class; treat it as read-only). */
internal data class AiControlsUiState(
    val story: Story?,
    val tab: AiControlsTab = AiControlsTab.COVER,
    /** This story's AI job in the shared slot (cover or description). */
    val aiJob: StoryOperationState? = null,
    /** This story's cover job: the slot, or a background job the slot has not caught up with yet. */
    val coverJob: StoryOperationState? = null,
    val coverDraft: AiCoverDraft? = null,
    val coverPrompt: CoverPrompt? = null,
    val descriptionDraft: String? = null,
    /** A character job finished while the Characters tab was not open. */
    val charactersUnseen: Boolean = false,
    /** Null until loaded (while the Cover tab is open). */
    val coverVersions: CoverVersions? = null,
    /** Null until loaded (while the Characters tab is open). */
    val characters: CharactersView? = null,
    val openCharacterId: String? = null,
    val supportingExpanded: Boolean = false,
    val characterJob: CharacterJobState? = null,
    /** Some novel's character job runs; one runs at a time. */
    val charactersBusy: Boolean = false,
    val settings: AiSettings = AiSettings(),
    val costs: AiCostLines = AiCostLines(),
    val coverPhases: List<StoryPhase> = emptyList(),
    val automaticCoverChapters: List<Int>? = null,
    val automaticDescriptionChapters: List<Int>? = null,
) {
    val characterPageOpen: Boolean get() = tab == AiControlsTab.CHARACTERS && openCharacterId != null

    /**
     * What the tab's layout depends on: two states with the same key differ only in progress
     * messages, typed prompt text, or the parts that load into their own slot ([coverVersions],
     * [characters]), which the renderer patches without rebuilding the tab.
     */
    fun layoutKey(): Any =
        Pair(
            story?.let(::AiStoryFingerprint),
            copy(
                story = null,
                aiJob = aiJob?.copy(message = ""),
                coverJob = coverJob?.copy(message = ""),
                characterJob = characterJob?.copy(message = ""),
                coverPrompt = CoverPrompt("", coverPrompt?.revision ?: 0),
                coverVersions = null,
                characters = null,
            ),
        )
}

/** The story fields the AI tab shows, so a download elsewhere in the story does not rebuild the tab. */
private data class AiStoryFingerprint(
    val title: String,
    val author: String,
    val chapterCount: Int,
    val anyDownloaded: Boolean,
    val archived: Boolean,
    val description: String?,
    val aiDescription: String?,
    val showAiDescription: Boolean,
    val coverUrl: String?,
    val aiCoverPath: String?,
    val aiCoverActive: Boolean,
    val coverChapters: List<Int>?,
    val descriptionChapters: List<Int>?,
    val coverPhase: String?,
) {
    constructor(story: Story) : this(
        story.title,
        story.author,
        story.chapters.size,
        story.chapters.any { it.downloaded },
        story.isArchived == true,
        story.description,
        story.aiDescription,
        story.showAiDescription,
        story.coverUrl,
        story.aiCoverPath,
        AiCoverPlanning.isAiCoverActive(story),
        story.aiCoverContextChapterIndices?.toList(),
        story.aiContextChapterIndices?.toList(),
        story.aiCoverPhase,
    )
}

/** What the AI tab reads and writes on the device (fake it in tests). */
internal interface AiControlsData {
    /** The story as the library publishes it now: shared, so only compared by identity. */
    fun published(storyId: String): Story?

    /** A copy of the story the caller may hold on to. */
    fun story(storyId: String): Story?

    /** Stories whose finished cover result was not looked at yet (persisted). */
    val unseenCovers: StateFlow<Map<String, Any>>

    /** Emits when the published library changes (e.g. a chapter downloads while the AI tab is open). */
    val libraryChanges: Flow<Any?> get() = emptyFlow()

    fun aiSettings(): AiSettings

    fun costs(storyId: String): AiCostLines

    fun coverPhases(storyId: String): List<StoryPhase>

    fun automaticChapters(
        storyId: String,
        forCover: Boolean,
    ): List<Int>?

    suspend fun markCoverSeen(storyId: String)

    suspend fun loadCoverWork(storyId: String): SavedCoverWork?

    /** Whether [draft] is byte for byte the AI cover already in use. */
    suspend fun isCoverInUse(
        storyId: String,
        draft: AiCoverDraft,
    ): Boolean

    suspend fun saveCoverPrompt(
        storyId: String,
        prompt: String,
    )

    suspend fun deleteCoverWork(storyId: String)

    suspend fun useCover(
        storyId: String,
        bytes: ByteArray,
        mediaType: String?,
    )

    suspend fun useSourceCover(storyId: String)

    /** The saved versions, marking the one whose image is [pending]'s. */
    suspend fun coverVersions(
        storyId: String,
        pending: AiCoverDraft?,
    ): CoverVersions

    /** A saved version's image, or null once it is gone. */
    suspend fun readVersion(version: AiCoverVersion): ByteArray?

    suspend fun deleteCoverVersion(
        storyId: String,
        versionId: String,
    ): Boolean

    suspend fun setAiDescription(
        storyId: String,
        description: String,
    )

    suspend fun setShowAiDescription(
        storyId: String,
        show: Boolean,
    )

    /** Saves the chapters sent to AI (null = automatic); returns the saved story, or null when it is gone. */
    suspend fun setContextChapters(
        storyId: String,
        forCover: Boolean,
        indices: List<Int>?,
    ): Story?

    suspend fun setCoverPhase(
        storyId: String,
        stageKey: String?,
    ): Story?

    suspend fun characters(
        storyId: String,
        openId: String?,
    ): CharactersView

    suspend fun deletePortrait(
        storyId: String,
        characterId: String,
        portraitId: String,
    )
}

/** The process-wide AI jobs the tab starts and watches (fake it in tests). */
internal interface AiControlsJobs {
    val coverJobs: StateFlow<Map<String, AiCoverJobState>>
    val characterJobs: StateFlow<Map<String, CharacterJobState>>

    /** Each start returns false when its coordinator is busy. */
    fun startCover(
        storyId: String,
        kind: AiCoverJobKind,
        prompt: String?,
    ): Boolean

    fun startDescription(storyId: String): Boolean

    fun startFindCharacters(storyId: String): Boolean

    fun startPortrait(
        storyId: String,
        characterId: String,
    ): Boolean
}

/**
 * The AI tab of one story: Cover, Characters and Description. It combines the shared AI slot
 * ([StoryOperations.ai]), the cover and character jobs, the results waiting in [AiAttention], the
 * persisted unseen covers, and what it loads itself (saved cover versions, the character list),
 * and it runs the tab's actions. Toasts go out through [messages].
 *
 * It watches its sources only while a renderer collects [state]. While watched, the open sub-tab
 * counts as looked at: its unseen marker, chip failure and dismissals are cleared, the way a visit
 * cleared them before.
 */
internal class AiControlsScreenModel(
    val storyId: String,
    private val data: AiControlsData,
    private val jobs: AiControlsJobs,
    private val attention: AiAttention,
    private val operations: StoryOperations,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : ScreenModel(scope) {
    private var tab = AiControlsTab.COVER
    private var openCharacterId: String? = null
    private var supportingExpanded = false
    private var publishedStory: Story? = null
    private var story: Story? = null
    private var coverVersions: CoverVersions? = null
    private var characters: CharactersView? = null
    private var watched = false
    private var versionsKey: Any? = null
    private var charactersKey: Any? = null
    private var reloads = 0
    private var settledDraft: AiCoverDraft? = null
    private var markingCoverSeen = false
    private val mutableState = MutableStateFlow(compute())
    private val messageChannel = Channel<String>(MESSAGE_BUFFER, BufferOverflow.DROP_OLDEST)

    val state: StateFlow<AiControlsUiState> = mutableState.asStateFlow()

    /** One-off messages for the user, buffered until a renderer shows them. */
    val messages: Flow<String> = messageChannel.receiveAsFlow()

    init {
        this.scope.launch {
            mutableState.subscriptionCount
                .map { it > 0 }
                .distinctUntilChanged()
                .collectLatest { observed ->
                    watched = observed
                    if (!observed) return@collectLatest
                    versionsKey = null
                    charactersKey = null
                    coroutineScope {
                        launch { hydrateCoverWork() }
                        merge(
                            operations.ai,
                            jobs.coverJobs,
                            jobs.characterJobs,
                            attention.state,
                            data.unseenCovers,
                            data.libraryChanges,
                        ).collect { refresh() }
                    }
                }
        }
    }

    /** Recomputes the state from its sources now and returns it. */
    fun refresh(): AiControlsUiState {
        val next = compute()
        mutableState.value = next
        if (watched) react(next)
        return next
    }

    fun selectTab(next: AiControlsTab) {
        if (tab == next) return
        tab = next
        refresh()
    }

    fun openCharacter(characterId: String) {
        openCharacterId = characterId
        refresh()
    }

    fun closeCharacterPage() {
        openCharacterId = null
        refresh()
    }

    fun toggleSupporting() {
        supportingExpanded = !supportingExpanded
        refresh()
    }

    // Description

    /** Only another AI job blocks this one; sync, cleanup and EPUB runs are independent. */
    fun startDescription() {
        if (operations.ai.value != null || !jobs.startDescription(storyId)) {
            message(WAIT_FOR_AI)
            return
        }
        attention.markSeen(storyId, AiControlsTab.DESCRIPTION)
        operations.setAi(StoryOperationState(storyId, StoryOperationKind.AI_DESCRIPTION, AiDescriptionJobCoordinator.STARTING_MESSAGE))
        refresh()
    }

    fun applyDescription(draft: String) {
        scope.launch {
            data.setAiDescription(storyId, draft)
            attention.setDescriptionDraft(storyId, null)
            message("AI description applied")
            refresh()
        }
    }

    fun discardDescription() {
        attention.setDescriptionDraft(storyId, null)
        message("Draft discarded")
        refresh()
    }

    fun showAiDescription(show: Boolean) {
        scope.launch {
            data.setShowAiDescription(storyId, show)
            refresh()
        }
    }

    // Cover

    /**
     * Starts a cover job on the process-wide coordinator: [AiCoverJobKind.IMAGE] paints [prompt],
     * [AiCoverJobKind.PROMPT] only writes a prompt, [AiCoverJobKind.ONE_STEP] does both.
     */
    fun startCover(
        kind: AiCoverJobKind,
        prompt: String? = null,
    ) {
        if (operations.ai.value != null) {
            message(WAIT_FOR_AI)
            return
        }
        if (!jobs.startCover(storyId, kind, prompt)) {
            message("Please wait for the current cover generation to finish")
            return
        }
        if (kind == AiCoverJobKind.IMAGE && prompt != null) {
            // The coordinator persists this prompt before calling the image model.
            attention.setCoverPrompt(storyId, prompt)
            attention.setCoverDraft(storyId, null)
        }
        attention.markSeen(storyId, AiControlsTab.COVER)
        // Optimistic, for the first redraw; the job bridge mirrors the coordinator into the slot.
        operations.setAi(StoryOperationState(storyId, StoryOperationKind.AI_COVER, startingMessage(kind)))
        refresh()
    }

    /** Typing in the prompt box: kept for the next render without redrawing the tab. */
    fun typeCoverPrompt(text: String) = attention.setCoverPrompt(storyId, text, typed = true)

    /** Empties the prompt box. A waiting new cover keeps its own record until the user decides on it. */
    fun clearCoverPrompt() {
        attention.setCoverPrompt(storyId, "")
        scope.launch {
            attempt { if (attention.state.value.coverDrafts[storyId] == null) data.deleteCoverWork(storyId) }
            refresh()
        }
    }

    /** Closes the "New cover ready" decision; the image is in the saved covers either way. */
    fun resolveCoverDraft(
        draft: AiCoverDraft,
        use: Boolean,
    ) {
        if (state.value.story?.isArchived == true) return
        scope.launch {
            attempt {
                if (use) data.useCover(storyId, draft.bytes, draft.mediaType)
                closeCoverDraft(draft.prompt)
                if (use) message("AI cover applied")
            }
            reloadCoverParts()
        }
    }

    /**
     * Makes a saved version (null = the source cover) the displayed cover, settling a waiting new
     * cover too. False when it cannot be used now; otherwise [onSettled] reports whether it was saved.
     */
    fun useCoverVersion(
        version: AiCoverVersion?,
        onSettled: (used: Boolean) -> Unit = {},
    ): Boolean {
        // A running cover job only writes a draft, never the cover in use, so choosing one is safe meanwhile.
        if (state.value.story?.isArchived == true) return false
        scope.launch {
            var used = false
            try {
                attempt {
                    if (version == null) {
                        data.useSourceCover(storyId)
                    } else {
                        val bytes = data.readVersion(version)
                        if (bytes == null) {
                            message("This cover is no longer available")
                            return@attempt
                        }
                        data.useCover(storyId, bytes, version.mediaType)
                    }
                    used = true
                    attention.state.value.coverDrafts[storyId]
                        ?.let { closeCoverDraft(it.prompt) }
                }
            } finally {
                onSettled(used)
            }
            reloadCoverParts()
        }
        return true
    }

    fun deleteCoverVersion(version: AiCoverVersion) {
        scope.launch {
            attempt { message(if (data.deleteCoverVersion(storyId, version.id)) "Cover deleted" else "This cover can't be deleted") }
            reloadCoverParts()
        }
    }

    /** Reusing a prompt is free: it only fills the box. */
    fun reuseCoverPrompt(prompt: String) {
        scope.launch {
            attempt {
                attention.state.value.coverDrafts[storyId]
                    ?.let { closeCoverDraft(it.prompt) }
                data.saveCoverPrompt(storyId, prompt)
                attention.setCoverPrompt(storyId, prompt)
            }
            reloadCoverParts()
        }
    }

    fun saveContextChapters(
        forCover: Boolean,
        indices: List<Int>?,
    ) {
        scope.launch {
            if (data.setContextChapters(storyId, forCover, indices) != null) message("Chapters sent to AI updated")
            refresh()
        }
    }

    fun setCoverPhase(stageKey: String?) {
        scope.launch {
            data.setCoverPhase(storyId, stageKey)
            refresh()
        }
    }

    /** Manual cover chapters bypass the scan that finds phases; this returns to automatic selection. */
    fun useAutomaticCoverChapters() {
        scope.launch {
            if (data.setContextChapters(storyId, forCover = true, indices = null) == null) return@launch
            val found = data.coverPhases(storyId).size > 1
            message(if (found) "Automatic chapters on" else "Automatic chapters on. Phases are found on the next cover.")
            refresh()
        }
    }

    // Characters

    fun startFindCharacters() {
        if (!jobs.startFindCharacters(storyId)) message(WAIT_FOR_CHARACTERS) else refresh()
    }

    fun startPortrait(character: StoryCharacter) {
        // The lead's evidence is the cover scan: running both at once would judge (and bill) chapters twice.
        if (character.role == CharacterRole.PROTAGONIST && refresh().coverJob != null) {
            message("Wait for the cover to finish, then paint the lead")
            return
        }
        if (!jobs.startPortrait(storyId, character.id)) message(WAIT_FOR_CHARACTERS) else refresh()
    }

    fun deletePortrait(
        characterId: String,
        portraitId: String,
    ) {
        scope.launch {
            runCatching { data.deletePortrait(storyId, characterId, portraitId) }
                .onFailure { if (it is CancellationException) throw it else message("Could not delete the portrait") }
            reloads += 1
            refresh()
        }
    }

    private fun compute(): AiControlsUiState {
        val published = data.published(storyId)
        if (published == null) {
            story = null
        } else if (published !== publishedStory || story == null) {
            story = data.story(storyId)
        }
        publishedStory = published
        val ai = operations.aiFor(storyId)
        val coverJob =
            ai?.takeIf { it.kind == StoryOperationKind.AI_COVER }
                ?: jobs.coverJobs.value[storyId]?.let { StoryOperationState(it.storyId, StoryOperationKind.AI_COVER, it.message) }
        val held = attention.state.value
        val characterJobs = jobs.characterJobs.value
        return AiControlsUiState(
            story = story,
            tab = tab,
            aiJob = ai,
            coverJob = coverJob,
            coverDraft = held.coverDrafts[storyId],
            coverPrompt = held.coverPrompts[storyId],
            descriptionDraft = held.descriptionDrafts[storyId],
            charactersUnseen = storyId in held.unseenCharacters,
            coverVersions = coverVersions,
            characters = characters,
            openCharacterId = openCharacterId,
            supportingExpanded = supportingExpanded,
            characterJob = characterJobs[storyId],
            charactersBusy = characterJobs.isNotEmpty(),
            settings = data.aiSettings(),
            costs = data.costs(storyId),
            coverPhases = data.coverPhases(storyId),
            automaticCoverChapters = data.automaticChapters(storyId, forCover = true),
            automaticDescriptionChapters = data.automaticChapters(storyId, forCover = false),
        )
    }

    /** While watched: the open sub-tab is looked at, and what it shows is loaded or settled. */
    private fun react(state: AiControlsUiState) {
        attention.markSeen(storyId, state.tab)
        when (state.tab) {
            AiControlsTab.COVER -> {
                markCoverSeen()
                loadCoverVersions(state)
                settleDraftInUse(state.coverDraft)
            }
            AiControlsTab.CHARACTERS -> loadCharacters(state)
            AiControlsTab.DESCRIPTION -> Unit
        }
    }

    private fun markCoverSeen() {
        if (storyId !in data.unseenCovers.value || markingCoverSeen) return
        markingCoverSeen = true
        scope.launch {
            try {
                data.markCoverSeen(storyId)
            } finally {
                markingCoverSeen = false
            }
        }
    }

    /** Reloads the strip when a job, the waiting cover, the cover in use or an action changed it. */
    private fun loadCoverVersions(state: AiControlsUiState) {
        val story = state.story ?: return
        val pending = state.coverDraft
        val key = listOf(state.coverJob != null, pending, story.aiCoverPath, story.showAiCover, reloads)
        if (key == versionsKey) return
        versionsKey = key
        scope.launch {
            val loaded = attempt { data.coverVersions(storyId, pending) } ?: return@launch
            if (versionsKey != key) return@launch
            coverVersions = loaded
            refresh()
        }
    }

    /** Reloads the list when the open character changes, a character job starts or ends, or a portrait is deleted. */
    private fun loadCharacters(state: AiControlsUiState) {
        val openId = state.openCharacterId
        val key = listOf(openId, state.characterJob?.let { it.kind to it.characterId }, reloads)
        if (key == charactersKey) return
        charactersKey = key
        scope.launch {
            val fresh = data.characters(storyId, openId)
            if (charactersKey != key) return@launch
            characters = fresh
            // A character the new list no longer has closes its page.
            if (openId != null && fresh.roster?.none { it.id == openId } != false) openCharacterId = null
            refresh()
        }
    }

    /** Older app versions could apply a draft without closing it; one identical to the cover in use is not news. */
    private fun settleDraftInUse(draft: AiCoverDraft?) {
        if (draft == null || draft === settledDraft) return
        settledDraft = draft
        scope.launch {
            if (!data.isCoverInUse(storyId, draft)) return@launch
            attempt { closeCoverDraft(draft.prompt) }
            refresh()
        }
    }

    /** Shows cover work persisted while the tab was closed (possibly by an earlier activity). Memory wins over disk. */
    private suspend fun hydrateCoverWork() {
        if (attention.state.value.coverDrafts[storyId] != null) return
        when (val saved = data.loadCoverWork(storyId) ?: return) {
            is SavedCoverWork.Prompt ->
                if (attention.state.value.coverPrompts[storyId] ==
                    null
                ) {
                    attention.setCoverPrompt(storyId, saved.prompt)
                }
            // The preview card shows the prompt too, so don't re-seed the editor once a preview exists.
            is SavedCoverWork.Image -> if (attention.state.value.coverDrafts[storyId] == null) attention.setCoverDraft(storyId, saved.draft)
        }
    }

    /** Turns a waiting cover back into a prompt-only draft (the store keeps the image in its history). */
    private suspend fun closeCoverDraft(prompt: String) {
        if (attention.state.value.coverDrafts[storyId] == null) return
        attention.setCoverDraft(storyId, null)
        if (prompt.isNotBlank()) {
            data.saveCoverPrompt(storyId, prompt)
            attention.setCoverPrompt(storyId, prompt)
        }
    }

    private fun reloadCoverParts() {
        reloads += 1
        refresh()
    }

    /** Keeps storage failures recoverable without swallowing cancellation. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> attempt(action: suspend () -> T): T? =
        try {
            action()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Timber.w(error, "Could not load or save AI cover work")
            message("Could not load or save cover work. Check available storage and try again.")
            null
        }

    private fun message(text: String) {
        messageChannel.trySend(text)
    }

    private companion object {
        const val MESSAGE_BUFFER = 8
        const val WAIT_FOR_AI = "Please wait for the current AI generation to finish"
        const val WAIT_FOR_CHARACTERS = "Please wait for the current character job to finish"

        fun startingMessage(kind: AiCoverJobKind): String =
            when (kind) {
                AiCoverJobKind.ONE_STEP -> "Generating cover..."
                AiCoverJobKind.PROMPT -> "Writing image prompt..."
                AiCoverJobKind.IMAGE -> "Painting cover..."
            }
    }
}
