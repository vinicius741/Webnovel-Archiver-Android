package com.vinicius741.webnovelarchiver.feature.settings

import com.vinicius741.webnovelarchiver.appupdate.AppUpdateState
import com.vinicius741.webnovelarchiver.domain.model.TtsSettings
import com.vinicius741.webnovelarchiver.narration.NarrationQueueDocument
import com.vinicius741.webnovelarchiver.narration.NarrationServer
import com.vinicius741.webnovelarchiver.narration.NarrationServerStatus
import com.vinicius741.webnovelarchiver.source.PatreonAccount
import com.vinicius741.webnovelarchiver.source.PatreonSession
import com.vinicius741.webnovelarchiver.source.network.HttpNetworkException
import com.vinicius741.webnovelarchiver.tts.TtsEngineInfo
import com.vinicius741.webnovelarchiver.tts.VoiceInfo
import com.vinicius741.webnovelarchiver.tts.kokoro.BuiltInModel
import com.vinicius741.webnovelarchiver.tts.kokoro.KokoroModelState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pages that follow live sources or run async work: App updates, Patreon account and Listening. */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsLiveModelsTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    // App updates

    @Test
    fun appUpdatesFollowTheCheckerWhileShownAndKeepTheSwitchOutOfTheBuild() =
        runTest(dispatcher) {
            val data = FakeAppUpdateData()
            val model = AppUpdateScreenModel(data, scope)
            val shown = model.refresh()
            val render = launch { model.state.collect() }
            runCurrent()
            assertEquals("the current checker state does not rebuild", shown, model.state.value)

            model.checkNow()
            assertEquals(1, data.checks)
            data.updates.value = AppUpdateState.Checking
            runCurrent()
            assertEquals(AppUpdateState.Checking, model.state.value.update)

            val buildKey = model.state.value.buildKey
            model.setAutoCheck(false)
            assertFalse(data.autoCheckEnabled)
            assertEquals(buildKey, model.state.value.buildKey)
            render.cancel()
        }

    @Test
    fun returningToAppUpdatesRereadsTheCheckedTime() =
        runTest(dispatcher) {
            val data = FakeAppUpdateData()
            val model = AppUpdateScreenModel(data, scope)
            val before = model.refresh()
            data.lastCheckedAtMillis = 5_000L
            data.clock = 9_000L
            val after = model.refresh()
            assertTrue(after.buildKey != before.buildKey)
            assertEquals(5_000L, after.lastCheckedAtMillis)
            assertEquals(9_000L, after.readAtMillis)
        }

    // Patreon account

    @Test
    fun aSavedSessionIsCheckedOnceAndReturningFromFirefoxDoesNotAskAgain() =
        runTest(dispatcher) {
            val data = FakePatreonData(session = "saved")
            val model = PatreonAccountScreenModel(data, storyId = null, scope)
            assertEquals(AccountStatus.Checking, model.refresh().status)
            runCurrent()
            assertTrue(model.state.value.signedIn)
            assertEquals(1, data.checks)

            data.firefox = true
            assertTrue(model.refresh().firefoxInstalled)
            runCurrent()
            assertEquals(1, data.checks)
        }

    @Test
    fun anAcceptedPasteIsWipedFromTheClipboardAndARefusedOneRestoresTheOldSession() =
        runTest(dispatcher) {
            val data = FakePatreonData()
            val model = PatreonAccountScreenModel(data, storyId = "s1", scope)
            model.refresh()
            assertEquals(AccountStatus.SignedOut, model.state.value.status)
            assertEquals("Early Bird", model.state.value.earlyAccessTitle)

            model.paste(null)
            assertEquals("The clipboard is empty. Copy your sign-in in Firefox first (step 4).", model.state.value.pasteFeedback)

            model.paste("session_id=new")
            assertEquals(AccountStatus.Checking, model.state.value.status)
            assertNull(model.state.value.pasteFeedback)
            runCurrent()
            assertTrue(model.state.value.signedIn)
            assertTrue(data.clipboardCleared)

            data.reject = true
            model.expandSteps()
            assertTrue(model.state.value.stepsExpanded)
            assertTrue(model.enterManually("session_id=bad"))
            runCurrent()
            assertEquals("the refused value never replaces the saved one", "new", data.session)
            assertEquals(
                "Patreon didn't accept that sign-in. Copy session_id again while signed in on patreon.com.",
                model.state.value.pasteFeedback,
            )
            assertFalse(model.enterManually("nonsense"))
        }

    @Test
    fun anUnreachablePatreonKeepsTheSessionAndSignOutClearsIt() =
        runTest(dispatcher) {
            val data = FakePatreonData(session = "saved").apply { failure = IllegalStateException("offline") }
            val model = PatreonAccountScreenModel(data, storyId = null, scope)
            model.refresh()
            runCurrent()
            assertEquals(AccountStatus.Unreachable("offline"), model.state.value.status)

            model.signOut()
            assertNull(data.session)
            assertEquals(AccountStatus.SignedOut, model.state.value.status)
        }

    // Listening

    @Test
    fun listeningChangesShowAtOnceAndSaveOnceTheySettle() =
        runTest(dispatcher) {
            val data = FakeTtsData()
            val unsaved = UnsavedSetting<TtsSettings>()
            val model = TtsSettingsScreenModel(data, scope, unsaved, scope)
            val buildKey = model.state.value.buildKey

            model.update { it.copy(pitch = 1.2f) }
            model.update { it.copy(sentencePauseMs = 300) }
            assertEquals(1.2f, model.state.value.settings.pitch)
            assertEquals("a stepper does not rebuild the page", buildKey, model.state.value.buildKey)
            assertEquals(
                "a quick return shows it",
                300L,
                TtsSettingsScreenModel(data, scope, unsaved, scope)
                    .state.value.settings.sentencePauseMs,
            )
            advanceTimeBy(401)
            runCurrent()
            assertEquals(1, data.persisted.size)
            assertEquals(1.2f, data.persisted.single().pitch)
        }

    @Test
    fun choosingAnEngineSavesAtOnceWithTheDraftAndResetsTheVoice() =
        runTest(dispatcher) {
            val data = FakeTtsData()
            data.settings = data.settings.copy(voiceIdentifier = "en-us-x")
            val model = TtsSettingsScreenModel(data, scope, UnsavedSetting(), scope)
            model.update { it.copy(phrasePauseMs = 50) }

            val message = model.chooseEngine(BuiltInModel.KOKORO_V1.engineId)
            runCurrent()
            assertEquals("Engine changed. Chapters generate on your computers until you download it here.", message)
            val saved = data.persisted.single()
            assertEquals(BuiltInModel.KOKORO_V1.engineId, saved.enginePackage)
            assertNull(saved.voiceIdentifier)
            assertEquals("the stepper change is not lost", 50L, saved.phrasePauseMs)
            assertEquals(listOf(BuiltInModel.KOKORO_V1), model.state.value.modelCards)

            advanceTimeBy(1_000)
            runCurrent()
            assertEquals("nothing older is saved over it", 1, data.persisted.size)
        }

    @Test
    fun removingTheChosenModelFallsBackToThePhonesEngine() =
        runTest(dispatcher) {
            val data = FakeTtsData()
            data.settings = data.settings.copy(enginePackage = BuiltInModel.KOKORO_V1.engineId)
            data.states.getValue(BuiltInModel.KOKORO_V1).value = KokoroModelState.Installed(10L)
            val model = TtsSettingsScreenModel(data, scope, UnsavedSetting(), scope)
            assertTrue(model.state.value.installed(BuiltInModel.KOKORO_V1))

            assertEquals("Kokoro v1 removed. Speaking with the phone's engine again.", model.removeModel(BuiltInModel.KOKORO_V1))
            runCurrent()
            assertEquals(listOf(BuiltInModel.KOKORO_V1), data.removed)
            assertNull(data.persisted.single().enginePackage)
            assertNull("another model's removal changes nothing", model.removeModel(BuiltInModel.KOKORO_V019))
        }

    @Test
    fun downloadsAndOnlineChecksUpdateInPlaceAndAddedComputersRebuild() =
        runTest(dispatcher) {
            val data = FakeTtsData()
            val model = TtsSettingsScreenModel(data, scope, UnsavedSetting(), scope)
            val render = launch { model.state.collect() }
            runCurrent()
            val buildKey = model.state.value.buildKey

            data.states.getValue(BuiltInModel.KOKORO_V019).value = KokoroModelState.Downloading(1L, 10L)
            runCurrent()
            assertEquals(KokoroModelState.Downloading(1L, 10L), model.state.value.modelStates[BuiltInModel.KOKORO_V019])
            assertTrue("an installing model gets a card", BuiltInModel.KOKORO_V019 in model.state.value.modelCards)

            data.states.getValue(BuiltInModel.KOKORO_V019).value = KokoroModelState.Downloading(5L, 10L)
            val cards = model.state.value.buildKey
            runCurrent()
            assertEquals("progress updates in place", cards, model.state.value.buildKey)
            assertTrue(buildKey != cards)

            data.statuses.value = mapOf("pc" to NarrationServerStatus("k", 0L, online = true))
            runCurrent()
            assertEquals(setOf("pc"), model.state.value.serverStatus.keys)

            data.narration.value = NarrationQueueDocument(servers = listOf(NarrationServer(id = "pc", name = "Desk")))
            runCurrent()
            assertTrue("a computer added rebuilds", model.state.value.buildKey != cards)
            render.cancel()
        }

    @Test
    fun theComputersAreCheckedOnlyWhileCollectedAndInFront() =
        runTest(dispatcher) {
            val data = FakeTtsData()
            data.narration.value = NarrationQueueDocument(servers = listOf(NarrationServer(id = "pc")))
            val model = TtsSettingsScreenModel(data, scope, UnsavedSetting(), scope)
            var front = true
            val ticks = launch { model.serverChecks { front }.take(3).toList() }
            runCurrent()
            assertEquals(1, data.refreshes)
            front = false
            advanceTimeBy(5_001)
            runCurrent()
            assertEquals("in the background it waits", 1, data.refreshes)
            front = true
            advanceTimeBy(5_001)
            runCurrent()
            assertEquals(2, data.refreshes)
            ticks.cancel()

            data.narration.value = NarrationQueueDocument()
            val noComputers = TtsSettingsScreenModel(data, scope, UnsavedSetting(), scope)
            assertEquals("no computers, no checks", emptyList<Unit>(), noComputers.serverChecks { true }.toList())
        }

    @Test
    fun theSampleKeepsItsTextAndComputers() =
        runTest(dispatcher) {
            val data = FakeTtsData()
            val a = NarrationServer(id = "a")
            val b = NarrationServer(id = "b")
            data.narration.value = NarrationQueueDocument(servers = listOf(a, b), generateOnIds = listOf("a"))
            val model = TtsSettingsScreenModel(data, scope, UnsavedSetting(), scope)
            assertEquals(listOf(a), model.chosenSampleComputers())

            model.toggleSampleComputer("b")
            assertEquals(listOf(a, b), model.chosenSampleComputers())
            model.setSampleText("Hello")
            assertEquals("Hello", model.sampleText)
        }

    @Test
    fun theSpeedTestReportsProgressStopsAndHandsOverItsReport() =
        runTest(dispatcher) {
            val data = FakeTtsData()
            val model = TtsSettingsScreenModel(data, scope, UnsavedSetting(), scope)
            model.startSpeedTest(BuiltInModel.KOKORO_V1)
            runCurrent()
            data.progress?.invoke("Step 1 of 4")
            assertEquals("Step 1 of 4", model.speedTest.value?.message)

            model.stopSpeedTest()
            data.progress?.invoke("Step 2 of 4")
            assertEquals("Stopping after the current sentence", model.speedTest.value?.message)
            assertTrue(data.stop?.invoke() == true)

            data.hasTest = true
            data.report.complete("Speed test\nfast")
            runCurrent()
            assertEquals("Speed test\nfast", model.speedTest.value?.report)
            assertTrue(BuiltInModel.KOKORO_V1 in model.state.value.speedTests)
            model.speedTestShown()
            assertNull(model.speedTest.value)
        }
}

private class FakeAppUpdateData : AppUpdateData {
    override val updates = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    override val installedVersionName = "1.2.2-native"
    override val releaseBuild = false
    override val checksEnabled = true
    override var autoCheckEnabled = true
    override val skippedTag: String? = null
    override var lastCheckedAtMillis = 0L
    var clock = 1_000L
    var checks = 0

    override fun checkNow() {
        checks++
    }

    override fun now(): Long = clock
}

private class FakePatreonData(
    var session: String? = null,
) : PatreonAccountData {
    var firefox = false
    var reject = false
    var failure: Exception? = null
    var checks = 0
    var clipboardCleared = false

    override fun session(): String? = session

    override fun storeSession(sessionId: String) {
        session = sessionId
    }

    override fun restoreSession(sessionId: String) {
        session = sessionId
    }

    override fun clearSession() {
        session = null
    }

    override fun parsePaste(text: String?): PatreonSession.Paste =
        when {
            text.isNullOrBlank() -> PatreonSession.Paste.Empty
            text.startsWith("session_id=") -> PatreonSession.Paste.Found(text.removePrefix("session_id="))
            else -> PatreonSession.Paste.Unrecognized
        }

    override suspend fun currentUser(): PatreonAccount {
        checks++
        failure?.let { throw it }
        if (reject) throw HttpNetworkException("https://www.patreon.com/api/current_user", 401)
        return PatreonAccount("Reader", emptyList())
    }

    override fun firefoxInstalled(): Boolean = firefox

    override fun earlyAccessTitle(storyId: String): String? = if (storyId == "s1") "Early Bird" else null

    override fun clearClipboard() {
        clipboardCleared = true
    }
}

private class FakeTtsData : TtsSettingsData {
    var settings = TtsSettings()
    val persisted = mutableListOf<TtsSettings>()
    val removed = mutableListOf<BuiltInModel>()
    var refreshes = 0
    var hasTest = false
    val report = CompletableDeferred<String>()
    var progress: ((String) -> Unit)? = null
    var stop: (() -> Boolean)? = null
    val states = BuiltInModel.entries.associateWith { MutableStateFlow<KokoroModelState>(KokoroModelState.NotInstalled()) }
    override val narration = MutableStateFlow(NarrationQueueDocument())
    val statuses = MutableStateFlow<Map<String, NarrationServerStatus>>(emptyMap())
    override val serverStatus: StateFlow<Map<String, NarrationServerStatus>> = statuses

    override fun settings(): TtsSettings = settings

    override suspend fun persist(settings: TtsSettings) {
        persisted += settings
        this.settings = settings
    }

    override fun engines(): List<TtsEngineInfo> = listOf(TtsEngineInfo(BuiltInModel.KOKORO_V1.engineId, "Kokoro v1.0 (built in)"))

    override fun phoneEngine(): String? = "com.google.android.tts"

    override fun voiceEngine(enginePackage: String?): String? = enginePackage

    override suspend fun voices(engine: String?): List<VoiceInfo>? = emptyList()

    override suspend fun refreshServerStatus(maxAgeMs: Long) {
        refreshes++
    }

    override fun modelState(model: BuiltInModel): StateFlow<KokoroModelState> = states.getValue(model)

    override fun install(model: BuiltInModel) = Unit

    override fun cancelInstall(model: BuiltInModel) = Unit

    override fun removeModel(model: BuiltInModel) {
        removed += model
        states.getValue(model).value = KokoroModelState.NotInstalled()
    }

    override fun hasSpeedTest(model: BuiltInModel): Boolean = hasTest && model == BuiltInModel.KOKORO_V1

    override suspend fun runSpeedTest(
        model: BuiltInModel,
        settings: TtsSettings,
        shouldStop: () -> Boolean,
        onProgress: (String) -> Unit,
    ): String {
        progress = onProgress
        stop = shouldStop
        return report.await()
    }

    override suspend fun lastSpeedTest(model: BuiltInModel): String? = null
}
