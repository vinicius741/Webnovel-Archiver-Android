package com.vinicius741.webnovelarchiver.feature.ai

import com.vinicius741.webnovelarchiver.ai.AiCoverDraft
import com.vinicius741.webnovelarchiver.ai.AiCoverJobKind
import com.vinicius741.webnovelarchiver.ai.AiCoverJobState
import com.vinicius741.webnovelarchiver.ai.CharacterJobKind
import com.vinicius741.webnovelarchiver.ai.CharacterJobState
import com.vinicius741.webnovelarchiver.data.storage.AiCoverVersion
import com.vinicius741.webnovelarchiver.domain.model.AiSettings
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.CharacterRole
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.model.StoryCharacter
import com.vinicius741.webnovelarchiver.domain.model.StoryPhase
import com.vinicius741.webnovelarchiver.operations.StoryOperationKind
import com.vinicius741.webnovelarchiver.operations.StoryOperationState
import com.vinicius741.webnovelarchiver.operations.StoryOperations
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class AiControlsScreenModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val story =
        Story(
            id = "s1",
            sourceUrl = "https://source.test/s1",
            title = "Story",
            chapters = mutableListOf(Chapter(id = "c1", downloaded = true)),
        )
    private val data = FakeData(story)
    private val jobs = FakeJobs()
    private val attention = AiAttention()
    private val operations = StoryOperations()
    private val draft = AiCoverDraft("a knight", byteArrayOf(1, 2, 3), "image/png")
    private val lead = character("lead", CharacterRole.PROTAGONIST)

    private fun model() =
        AiControlsScreenModel(
            storyId = "s1",
            data = data,
            jobs = jobs,
            attention = attention,
            operations = operations,
            scope = CoroutineScope(SupervisorJob() + dispatcher),
        )

    /** Collects the state the way an attached renderer does, and records the model's messages. */
    private fun TestScope.observe(model: AiControlsScreenModel): List<String> {
        val messages = mutableListOf<String>()
        backgroundScope.launch(dispatcher) { model.state.collect {} }
        backgroundScope.launch(dispatcher) { model.messages.toList(messages) }
        runCurrent()
        return messages
    }

    @Test
    fun tabsAndTheCharacterPageAreTheModelsOwnState() =
        runTest(dispatcher) {
            val model = model()
            assertEquals(AiControlsTab.COVER, model.state.value.tab)

            model.selectTab(AiControlsTab.CHARACTERS)
            model.openCharacter("lead")
            assertTrue(model.state.value.characterPageOpen)

            model.selectTab(AiControlsTab.COVER)
            assertFalse(model.state.value.characterPageOpen)

            model.selectTab(AiControlsTab.CHARACTERS)
            model.closeCharacterPage()
            assertNull(model.state.value.openCharacterId)
        }

    @Test
    fun theWatchedTabCountsAsLookedAt() =
        runTest(dispatcher) {
            attention.setDescriptionDraft("s1", "A synopsis")
            attention.markUnseen("s1", AiControlsTab.DESCRIPTION)
            attention.recordFailure("s1", AiControlsTab.DESCRIPTION, "Boom")
            val model = model()
            model.selectTab(AiControlsTab.DESCRIPTION)
            // Not watched yet: nothing is marked seen.
            assertEquals(setOf("s1"), attention.state.value.unseenDescriptions)

            observe(model)

            assertTrue(
                attention.state.value.unseenDescriptions
                    .isEmpty(),
            )
            assertTrue(
                attention.state.value.failures
                    .isEmpty(),
            )
            assertEquals("A synopsis", model.state.value.descriptionDraft)
        }

    @Test
    fun theCoverTabMarksAnUnseenCoverSeenAndLoadsTheVersions() =
        runTest(dispatcher) {
            data.unseen.value = mapOf("s1" to "image")
            val model = model()

            observe(model)

            assertEquals(listOf("s1"), data.markedSeen)
            assertEquals(
                listOf("v1"),
                model.state.value.coverVersions
                    ?.versions
                    ?.map { it.id },
            )
        }

    @Test
    fun aDraftSavedWhileTheTabWasClosedIsHydrated() =
        runTest(dispatcher) {
            data.savedWork = SavedCoverWork.Image(draft)
            val model = model()

            observe(model)

            assertEquals(draft, model.state.value.coverDraft)
            assertEquals(draft, attention.state.value.coverDrafts["s1"])
        }

    @Test
    fun startingACoverHoldsTheAiSlotAndSeedsThePromptBox() =
        runTest(dispatcher) {
            attention.setCoverDraft("s1", draft)
            val model = model()
            observe(model)

            model.startCover(AiCoverJobKind.IMAGE, "a dragon")

            assertEquals(listOf(AiCoverJobKind.IMAGE to "a dragon"), jobs.coverStarts)
            assertEquals(StoryOperationState("s1", StoryOperationKind.AI_COVER, "Painting cover..."), model.state.value.aiJob)
            assertEquals(
                "a dragon",
                model.state.value.coverPrompt
                    ?.text,
            )
            assertNull(model.state.value.coverDraft)
        }

    @Test
    fun anotherAiJobBlocksAStart() =
        runTest(dispatcher) {
            operations.setAi(StoryOperationState("s2", StoryOperationKind.AI_DESCRIPTION, "Busy"))
            val model = model()
            val messages = observe(model)

            model.startCover(AiCoverJobKind.ONE_STEP)
            model.startDescription()
            runCurrent()

            assertTrue(jobs.coverStarts.isEmpty())
            assertTrue(jobs.descriptionStarts.isEmpty())
            assertEquals(2, messages.count { it == "Please wait for the current AI generation to finish" })
        }

    @Test
    fun startingADescriptionHoldsTheAiSlot() =
        runTest(dispatcher) {
            val model = model()
            observe(model)

            model.startDescription()

            assertEquals(listOf("s1"), jobs.descriptionStarts)
            assertEquals(
                StoryOperationKind.AI_DESCRIPTION,
                model.state.value.aiJob
                    ?.kind,
            )
        }

    @Test
    fun usingTheWaitingCoverAppliesItAndKeepsItsPrompt() =
        runTest(dispatcher) {
            attention.setCoverDraft("s1", draft)
            val model = model()
            val messages = observe(model)

            model.resolveCoverDraft(draft, use = true)
            runCurrent()

            assertEquals(listOf(draft.bytes.toList()), data.usedCovers.map { it.toList() })
            assertNull(model.state.value.coverDraft)
            assertEquals(
                "a knight",
                model.state.value.coverPrompt
                    ?.text,
            )
            assertEquals("a knight", data.savedPrompts["s1"])
            assertTrue("AI cover applied" in messages)
        }

    @Test
    fun aWaitingCoverAlreadyInUseIsSettled() =
        runTest(dispatcher) {
            attention.setCoverDraft("s1", draft)
            data.coverInUse = true
            val model = model()

            observe(model)

            assertNull(model.state.value.coverDraft)
            assertEquals("a knight", data.savedPrompts["s1"])
        }

    @Test
    fun typingKeepsTheLayoutWhileClearingChangesIt() =
        runTest(dispatcher) {
            val model = model()
            observe(model)
            val before = model.state.value.layoutKey()

            model.typeCoverPrompt("a ca")
            model.typeCoverPrompt("a castle")
            assertEquals(before, model.refresh().layoutKey())
            assertEquals(
                "a castle",
                model.state.value.coverPrompt
                    ?.text,
            )

            model.clearCoverPrompt()
            runCurrent()
            assertNotEquals(before, model.state.value.layoutKey())
            assertEquals(
                "",
                model.state.value.coverPrompt
                    ?.text,
            )
            assertEquals(listOf("s1"), data.deletedWork)
        }

    @Test
    fun progressMessagesKeepTheLayout() =
        runTest(dispatcher) {
            val model = model()
            observe(model)
            operations.setAi(StoryOperationState("s1", StoryOperationKind.AI_COVER, "Painting cover..."))
            runCurrent()
            val started = model.state.value.layoutKey()

            operations.updateAi("s1", StoryOperationKind.AI_COVER, "Still painting...")
            runCurrent()

            assertEquals(
                "Still painting...",
                model.state.value.aiJob
                    ?.message,
            )
            assertEquals(started, model.state.value.layoutKey())
        }

    @Test
    fun charactersLoadOnTheirTabAndReloadWhenAJobEnds() =
        runTest(dispatcher) {
            data.roster = listOf(lead)
            val model = model()
            observe(model)
            assertNull(model.state.value.characters)

            model.selectTab(AiControlsTab.CHARACTERS)
            runCurrent()
            assertEquals(
                listOf(lead),
                model.state.value.characters
                    ?.roster,
            )
            val loads = data.characterLoads

            jobs.characterJobs.value = mapOf("s1" to CharacterJobState("s1", CharacterJobKind.FIND, null, "Reading chapters..."))
            runCurrent()
            jobs.characterJobs.value = emptyMap()
            runCurrent()

            assertEquals(loads + 2, data.characterLoads)
        }

    @Test
    fun aCharacterMissingFromTheListClosesItsPage() =
        runTest(dispatcher) {
            data.roster = listOf(lead)
            val model = model()
            observe(model)
            model.selectTab(AiControlsTab.CHARACTERS)
            model.openCharacter("gone")
            runCurrent()

            assertNull(model.state.value.openCharacterId)
            assertFalse(model.state.value.characterPageOpen)
        }

    @Test
    fun theLeadWaitsForARunningCover() =
        runTest(dispatcher) {
            jobs.coverJobs.value = mapOf("s1" to AiCoverJobState("s1", AiCoverJobKind.ONE_STEP, "Generating cover..."))
            val model = model()
            val messages = observe(model)

            model.startPortrait(lead)
            model.startPortrait(character("friend", CharacterRole.MAJOR))
            runCurrent()

            assertEquals(listOf("friend"), jobs.portraitStarts)
            assertTrue("Wait for the cover to finish, then paint the lead" in messages)
        }

    @Test
    fun applyingADescriptionSavesItAndDropsTheDraft() =
        runTest(dispatcher) {
            attention.setDescriptionDraft("s1", "A synopsis")
            val model = model()
            val messages = observe(model)

            model.applyDescription("A synopsis")
            runCurrent()

            assertEquals("A synopsis", data.aiDescription)
            assertNull(model.state.value.descriptionDraft)
            assertTrue("AI description applied" in messages)
        }

    private fun character(
        id: String,
        role: CharacterRole,
    ) = StoryCharacter(id = id, name = id, aliases = emptyList(), role = role, bio = "", mentions = 1, firstChapter = 1, lastChapter = 1)

    private class FakeJobs : AiControlsJobs {
        override val coverJobs = MutableStateFlow<Map<String, AiCoverJobState>>(emptyMap())
        override val characterJobs = MutableStateFlow<Map<String, CharacterJobState>>(emptyMap())
        val coverStarts = mutableListOf<Pair<AiCoverJobKind, String?>>()
        val descriptionStarts = mutableListOf<String>()
        val portraitStarts = mutableListOf<String>()

        override fun startCover(
            storyId: String,
            kind: AiCoverJobKind,
            prompt: String?,
        ): Boolean = coverStarts.add(kind to prompt)

        override fun startDescription(storyId: String): Boolean = descriptionStarts.add(storyId)

        override fun startFindCharacters(storyId: String): Boolean = true

        override fun startPortrait(
            storyId: String,
            characterId: String,
        ): Boolean = portraitStarts.add(characterId)
    }

    private class FakeData(
        private var current: Story,
    ) : AiControlsData {
        val unseen = MutableStateFlow<Map<String, Any>>(emptyMap())
        val markedSeen = mutableListOf<String>()
        val savedPrompts = mutableMapOf<String, String>()
        val deletedWork = mutableListOf<String>()
        val usedCovers = mutableListOf<ByteArray>()
        var savedWork: SavedCoverWork? = null
        var coverInUse = false
        var roster: List<StoryCharacter>? = null
        var characterLoads = 0
        var aiDescription: String? = null

        override fun published(storyId: String): Story? = current.takeIf { it.id == storyId }

        override fun story(storyId: String): Story? = published(storyId)?.copy()

        override val unseenCovers: StateFlow<Map<String, Any>> = unseen

        override fun aiSettings() = AiSettings()

        override fun costs(storyId: String) = AiCostLines()

        override fun coverPhases(storyId: String): List<StoryPhase> = emptyList()

        override fun automaticChapters(
            storyId: String,
            forCover: Boolean,
        ): List<Int>? = null

        override suspend fun markCoverSeen(storyId: String) {
            markedSeen += storyId
            unseen.value = unseen.value - storyId
        }

        override suspend fun loadCoverWork(storyId: String) = savedWork

        override suspend fun isCoverInUse(
            storyId: String,
            draft: AiCoverDraft,
        ) = coverInUse

        override suspend fun saveCoverPrompt(
            storyId: String,
            prompt: String,
        ) {
            savedPrompts[storyId] = prompt
        }

        override suspend fun deleteCoverWork(storyId: String) {
            deletedWork += storyId
        }

        override suspend fun useCover(
            storyId: String,
            bytes: ByteArray,
            mediaType: String?,
        ) {
            usedCovers += bytes
        }

        override suspend fun useSourceCover(storyId: String) = Unit

        override suspend fun coverVersions(
            storyId: String,
            pending: AiCoverDraft?,
        ) = CoverVersions(listOf(AiCoverVersion("v1", "a knight", "image/png", File("v1.png"), 1L)), null)

        override suspend fun readVersion(version: AiCoverVersion): ByteArray? = null

        override suspend fun deleteCoverVersion(
            storyId: String,
            versionId: String,
        ) = true

        override suspend fun setAiDescription(
            storyId: String,
            description: String,
        ) {
            aiDescription = description
            current = current.copy(aiDescription = description)
        }

        override suspend fun setShowAiDescription(
            storyId: String,
            show: Boolean,
        ) = Unit

        override suspend fun setContextChapters(
            storyId: String,
            forCover: Boolean,
            indices: List<Int>?,
        ): Story? = current

        override suspend fun setCoverPhase(
            storyId: String,
            stageKey: String?,
        ): Story? = current

        override suspend fun characters(
            storyId: String,
            openId: String?,
        ): CharactersView {
            characterLoads += 1
            return CharactersView(openId, roster, emptyMap(), emptyList())
        }

        override suspend fun deletePortrait(
            storyId: String,
            characterId: String,
            portraitId: String,
        ) = Unit
    }
}
