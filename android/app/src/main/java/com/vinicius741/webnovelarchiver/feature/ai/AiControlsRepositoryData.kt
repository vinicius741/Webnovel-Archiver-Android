package com.vinicius741.webnovelarchiver.feature.ai

import android.content.Context
import com.vinicius741.webnovelarchiver.ai.AiCoverDraft
import com.vinicius741.webnovelarchiver.ai.AiCoverJobCoordinator
import com.vinicius741.webnovelarchiver.ai.AiCoverJobKind
import com.vinicius741.webnovelarchiver.ai.AiCoverJobState
import com.vinicius741.webnovelarchiver.ai.AiCoverPlanning
import com.vinicius741.webnovelarchiver.ai.AiDescriptionJobCoordinator
import com.vinicius741.webnovelarchiver.ai.AiJobForegroundService
import com.vinicius741.webnovelarchiver.ai.CharacterJobCoordinator
import com.vinicius741.webnovelarchiver.ai.CharacterJobState
import com.vinicius741.webnovelarchiver.app.appContainer
import com.vinicius741.webnovelarchiver.data.repository.AppRepository
import com.vinicius741.webnovelarchiver.data.repository.characterPortraits
import com.vinicius741.webnovelarchiver.data.repository.characterRoster
import com.vinicius741.webnovelarchiver.data.repository.coverFile
import com.vinicius741.webnovelarchiver.data.repository.deleteAiCoverDraft
import com.vinicius741.webnovelarchiver.data.repository.deleteAiCoverVersion
import com.vinicius741.webnovelarchiver.data.repository.deleteCharacterPortrait
import com.vinicius741.webnovelarchiver.data.repository.getAiUsageLedger
import com.vinicius741.webnovelarchiver.data.repository.latestCharacterPortraits
import com.vinicius741.webnovelarchiver.data.repository.listAiCoverVersions
import com.vinicius741.webnovelarchiver.data.repository.loadAiCoverDraft
import com.vinicius741.webnovelarchiver.data.repository.markAiCoverResultSeen
import com.vinicius741.webnovelarchiver.data.repository.saveAiCoverPromptDraft
import com.vinicius741.webnovelarchiver.data.repository.setAiCover
import com.vinicius741.webnovelarchiver.data.repository.setAiCoverContextChapters
import com.vinicius741.webnovelarchiver.data.repository.setAiCoverPhase
import com.vinicius741.webnovelarchiver.data.repository.setShowAiCover
import com.vinicius741.webnovelarchiver.data.storage.AiCoverDraftRecord
import com.vinicius741.webnovelarchiver.data.storage.AiCoverVersion
import com.vinicius741.webnovelarchiver.data.storage.EvidenceSelectionKind
import com.vinicius741.webnovelarchiver.domain.model.AiSettings
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.model.StoryPhase
import com.vinicius741.webnovelarchiver.domain.sha256Hex
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * The AI tab's model for [storyId]. It is keyed by the AI route, not the Overview route the story
 * page's model uses: the store keeps one model per key, and every tab of a story stays in the
 * history together, so both models live exactly as long as the story's page.
 */
internal fun ScreenHost.aiControlsModel(storyId: String): AiControlsScreenModel =
    screenModels.get(AppRoute.AiControls(storyId).stableKey) {
        val container = app.appContainer
        AiControlsScreenModel(
            storyId = storyId,
            data =
                RepositoryAiControlsData(
                    repository,
                    phases = container.chapterEvidence::phases,
                    automaticChapters = { id, forCover ->
                        container.chapterEvidence.selection(
                            id,
                            if (forCover) EvidenceSelectionKind.COVER else EvidenceSelectionKind.DESCRIPTION,
                        )
                    },
                ),
            jobs =
                CoordinatorAiControlsJobs(
                    app.applicationContext,
                    container.aiCoverJobCoordinator,
                    container.aiDescriptionJobs,
                    container.characterJobCoordinator,
                ),
            attention = container.aiAttention,
            operations = operations,
        )
    }

/** [AiControlsData] backed by the repository and the evidence cache. */
private class RepositoryAiControlsData(
    private val repository: AppRepository,
    private val phases: (storyId: String) -> List<StoryPhase>,
    private val automaticChapters: (storyId: String, forCover: Boolean) -> List<Int>?,
) : AiControlsData {
    override fun published(storyId: String): Story? =
        repository.downloadState.value.library
            .firstOrNull { it.id == storyId }

    override fun story(storyId: String): Story? = repository.story(storyId)

    override val unseenCovers: StateFlow<Map<String, Any>> get() = repository.aiCoverUnseen

    override val libraryChanges: Flow<Any?> get() = repository.downloadState

    override fun aiSettings(): AiSettings = repository.aiSettings.get()

    override fun costs(storyId: String): AiCostLines {
        val ledger = repository.getAiUsageLedger()
        return AiCostLines(
            description = latestAiOperationCostLine(ledger, storyId, AI_FEATURE_DESCRIPTION),
            coverPrompt = latestAiOperationCostLine(ledger, storyId, AI_FEATURE_COVER_PROMPT),
            coverImage = latestAiOperationCostLine(ledger, storyId, AI_FEATURE_COVER_IMAGE),
        )
    }

    override fun coverPhases(storyId: String): List<StoryPhase> = phases(storyId)

    override fun automaticChapters(
        storyId: String,
        forCover: Boolean,
    ): List<Int>? = automaticChapters.invoke(storyId, forCover)

    override suspend fun markCoverSeen(storyId: String) = repository.markAiCoverResultSeen(storyId)

    override suspend fun loadCoverWork(storyId: String): SavedCoverWork? =
        when (val record = repository.loadAiCoverDraft(storyId)) {
            is AiCoverDraftRecord.PromptOnly -> SavedCoverWork.Prompt(record.prompt)
            is AiCoverDraftRecord.Image -> SavedCoverWork.Image(record.draft)
            null -> null
        }

    override suspend fun isCoverInUse(
        storyId: String,
        draft: AiCoverDraft,
    ): Boolean =
        withContext(Dispatchers.Default) {
            val story = repository.story(storyId) ?: return@withContext false
            val applied = repository.coverFile(story)?.takeIf { AiCoverPlanning.isAiCoverActive(story) } ?: return@withContext false
            runCatching { applied.readBytes() }.getOrNull()?.contentEquals(draft.bytes) == true
        }

    override suspend fun saveCoverPrompt(
        storyId: String,
        prompt: String,
    ) = repository.saveAiCoverPromptDraft(storyId, prompt)

    override suspend fun deleteCoverWork(storyId: String) = repository.deleteAiCoverDraft(storyId)

    override suspend fun useCover(
        storyId: String,
        bytes: ByteArray,
        mediaType: String?,
    ) {
        repository.setAiCover(storyId, bytes, mediaType)
    }

    override suspend fun useSourceCover(storyId: String) {
        repository.setShowAiCover(storyId, false)
    }

    override suspend fun coverVersions(
        storyId: String,
        pending: AiCoverDraft?,
    ): CoverVersions {
        val versions = repository.listAiCoverVersions(storyId)
        val newId = pending?.let { withContext(Dispatchers.Default) { sha256Hex(it.bytes) } }
        return CoverVersions(versions, newId)
    }

    override suspend fun readVersion(version: AiCoverVersion): ByteArray? =
        withContext(Dispatchers.IO) { runCatching { version.image.readBytes() }.getOrNull() }

    override suspend fun deleteCoverVersion(
        storyId: String,
        versionId: String,
    ): Boolean = repository.deleteAiCoverVersion(storyId, versionId)

    override suspend fun setAiDescription(
        storyId: String,
        description: String,
    ) {
        repository.setAiDescription(storyId, description)
    }

    override suspend fun setShowAiDescription(
        storyId: String,
        show: Boolean,
    ) {
        repository.setShowAiDescription(storyId, show)
    }

    override suspend fun setContextChapters(
        storyId: String,
        forCover: Boolean,
        indices: List<Int>?,
    ): Story? = if (forCover) repository.setAiCoverContextChapters(storyId, indices) else repository.setAiContextChapters(storyId, indices)

    override suspend fun setCoverPhase(
        storyId: String,
        stageKey: String?,
    ): Story? = repository.setAiCoverPhase(storyId, stageKey)

    override suspend fun characters(
        storyId: String,
        openId: String?,
    ): CharactersView {
        val roster = repository.characterRoster(storyId)
        val open = roster?.firstOrNull { it.id == openId }
        return CharactersView(
            openId = openId,
            roster = roster,
            thumbs =
                runCatching { repository.latestCharacterPortraits(storyId) }
                    .onFailure { if (it is CancellationException) throw it }
                    .getOrDefault(emptyMap()),
            portraits =
                open
                    ?.let {
                        runCatching { repository.characterPortraits(storyId, it.id) }
                            .onFailure { err -> if (err is CancellationException) throw err }
                            .getOrNull()
                    }.orEmpty(),
        )
    }

    override suspend fun deletePortrait(
        storyId: String,
        characterId: String,
        portraitId: String,
    ) {
        repository.deleteCharacterPortrait(storyId, characterId, portraitId)
    }
}

/** [AiControlsJobs] on the process-wide coordinators; a started cover or character job also starts the keep-alive service. */
private class CoordinatorAiControlsJobs(
    private val context: Context,
    private val covers: AiCoverJobCoordinator,
    private val descriptions: AiDescriptionJobCoordinator,
    private val characters: CharacterJobCoordinator,
) : AiControlsJobs {
    override val coverJobs: StateFlow<Map<String, AiCoverJobState>> get() = covers.jobs
    override val characterJobs: StateFlow<Map<String, CharacterJobState>> get() = characters.jobs

    override fun startCover(
        storyId: String,
        kind: AiCoverJobKind,
        prompt: String?,
    ): Boolean {
        val started =
            when (kind) {
                AiCoverJobKind.ONE_STEP -> covers.startOneShot(storyId)
                AiCoverJobKind.PROMPT -> covers.startPromptDraft(storyId)
                AiCoverJobKind.IMAGE -> covers.startImageDraft(storyId, requireNotNull(prompt))
            }
        if (started) AiJobForegroundService.start(context, "Generating AI cover...")
        return started
    }

    override fun startDescription(storyId: String): Boolean = descriptions.start(storyId)

    override fun startFindCharacters(storyId: String): Boolean =
        characters.startFind(storyId).also { if (it) AiJobForegroundService.start(context, "Finding characters...") }

    override fun startPortrait(
        storyId: String,
        characterId: String,
    ): Boolean =
        characters.startPortrait(storyId, characterId).also { if (it) AiJobForegroundService.start(context, "Painting a portrait...") }
}
