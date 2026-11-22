package com.vinicius741.webnovelarchiver.feature.settings

import com.vinicius741.webnovelarchiver.ai.OpenRouterKeyUsage
import com.vinicius741.webnovelarchiver.domain.model.AiSettings
import com.vinicius741.webnovelarchiver.domain.model.AiUsageLedger
import com.vinicius741.webnovelarchiver.domain.model.AppSettings
import com.vinicius741.webnovelarchiver.domain.model.SourceDownloadSettings
import com.vinicius741.webnovelarchiver.domain.model.Tab
import com.vinicius741.webnovelarchiver.narration.NarrationCompressionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
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

/** The Settings hub and its form pages: Download settings, Manage tabs and AI settings. */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsFormModelsTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    // Download settings

    @Test
    fun downloadChangesShowAtOnceAndSaveOnceTheySettle() =
        runTest(dispatcher) {
            val data = FakeDownloadData()
            val unsaved = UnsavedSetting<AppSettings>()
            val model = DownloadSettingsScreenModel(data, scope, unsaved, scope)

            model.setDelayMin(2_000)
            model.setDelayMin(3_000)
            assertEquals(PaceRange(3_000, 3_000), model.state.value.range)
            assertEquals("the hub sees the unsaved change", 3_000L, unsaved.shown { data.settings }.downloadDelay)
            advanceTimeBy(399)
            runCurrent()
            assertTrue("held steppers save once", data.saved.isEmpty())

            advanceTimeBy(2)
            runCurrent()
            assertEquals(listOf(3_000L), data.saved.map { it.downloadDelay })
            assertNull("cleared once saved", unsaved.value)
        }

    @Test
    fun downloadRangeKeepsMinBelowMaxAndLeavingFlushes() =
        runTest(dispatcher) {
            val data = FakeDownloadData()
            val model = DownloadSettingsScreenModel(data, scope, UnsavedSetting(), scope)

            assertEquals(PaceRange(4_000, 4_000), model.setDelayMin(4_000))
            assertEquals(PaceRange(1_000, 1_000), model.setDelayMax(1_000))
            val preset = DownloadPacePlanning.presets.first()
            assertEquals(preset.range, model.selectPace(0))
            model.setParallelSites(3)
            model.flush()
            runCurrent()
            assertEquals(1, data.saved.size)
            assertEquals(preset.range.minMs, data.saved.single().downloadDelay)
            assertEquals(3, data.saved.single().maxParallelSources)

            model.flush()
            runCurrent()
            assertEquals("nothing waiting, nothing saved", 1, data.saved.size)
        }

    @Test
    fun aReturnWhileTheSaveRunsShowsTheNewerValue() =
        runTest(dispatcher) {
            val data = FakeDownloadData()
            val unsaved = UnsavedSetting<AppSettings>()
            DownloadSettingsScreenModel(data, scope, unsaved, scope).setParallelSites(4)

            assertEquals(
                4,
                DownloadSettingsScreenModel(data, scope, unsaved, scope)
                    .state.value.settings.maxParallelSources,
            )
        }

    @Test
    fun aSitePaceIsSavedOrRemovedAndAdvancedToggles() =
        runTest(dispatcher) {
            val data = FakeDownloadData()
            val model = DownloadSettingsScreenModel(data, scope, UnsavedSetting(), scope)
            val buildKey = model.state.value.buildKey

            model.saveSourcePace("royalroad", PaceRange(2_000, 3_000))
            runCurrent()
            assertEquals(SourceDownloadSettings(concurrency = 1, delay = 2_000, delayMax = 3_000), model.state.value.overrides["royalroad"])
            assertTrue(model.state.value.buildKey != buildKey)

            model.saveSourcePace("royalroad", null)
            runCurrent()
            assertTrue(
                model.state.value.overrides
                    .isEmpty(),
            )

            model.toggleAdvanced()
            assertTrue(model.state.value.advancedOpen)
        }

    // Manage tabs

    @Test
    fun tabsAreAddedFromTheDraftMovedRenamedAndDeleted() =
        runTest(dispatcher) {
            val data = FakeTabsData()
            val model = TabsScreenModel(data, scope)

            model.setNewTabName("  ")
            assertFalse(model.addTab())
            model.setNewTabName("Reading")
            assertTrue(model.addTab())
            assertEquals("the draft stays until the save lands", "Reading", model.newTabName)
            runCurrent()
            assertEquals("", model.newTabName)
            model.setNewTabName("Later")
            model.addTab()
            runCurrent()
            assertEquals(
                listOf("Reading", "Later"),
                model.state.value.tabs
                    .map(Tab::name),
            )

            model.move(1, 0)
            runCurrent()
            assertEquals(
                listOf("Later", "Reading"),
                model.state.value.tabs
                    .map(Tab::name),
            )

            val reading = model.state.value.tabs[1]
            model.rename(reading.id, "Now")
            runCurrent()
            assertEquals(
                "Now",
                model.state.value.tabs[1]
                    .name,
            )

            data.counts = mapOf(reading.id to 2)
            model.refresh()
            assertEquals(2, model.state.value.novelCounts[reading.id])
            model.delete(reading.id)
            runCurrent()
            assertEquals(listOf(reading.id), data.unassigned)
            assertEquals(
                listOf("Later"),
                model.state.value.tabs
                    .map(Tab::name),
            )
        }

    // AI settings

    @Test
    fun theApiKeySavesTrimmedOnceTypingPausesAndAReturnShowsIt() =
        runTest(dispatcher) {
            val data = FakeAiData()
            val unsaved = UnsavedSetting<String>()
            val model = AiSettingsScreenModel(data, scope, unsaved, scope)

            model.setApiKey("sk-or")
            model.setApiKey(" sk-or-v1 ")
            assertEquals(" sk-or-v1 ", model.apiKey)
            assertEquals("a quick return shows the typed key", " sk-or-v1 ", AiSettingsScreenModel(data, scope, unsaved, scope).apiKey)
            advanceTimeBy(601)
            runCurrent()
            assertEquals("sk-or-v1", data.settings.apiKey)
            assertEquals(1, data.saves)

            model.setApiKey("")
            model.flush()
            runCurrent()
            assertNull("a blank key removes it", data.settings.apiKey)
        }

    @Test
    fun spendingComesFromTheLogUntilOpenRoutersFiguresLoad() =
        runTest(dispatcher) {
            val data = FakeAiData()
            val model = AiSettingsScreenModel(data, scope, UnsavedSetting(), scope)
            model.refresh()
            runCurrent()
            assertNull("no key, no OpenRouter call", model.state.value.keyUsage)
            assertEquals(0, data.usageCalls)

            data.settings = data.settings.copy(apiKey = "sk-or-v1")
            model.refresh()
            runCurrent()
            assertEquals(
                "1.25",
                model.state.value.keyUsage
                    ?.usage,
            )

            data.failUsage = true
            model.refresh()
            runCurrent()
            assertEquals(
                "a failed fetch keeps the last figures",
                "1.25",
                model.state.value.keyUsage
                    ?.usage,
            )
        }

    @Test
    fun aModelPickSavesAndRefreshesTheRowsAndTheScanLimitSaves() =
        runTest(dispatcher) {
            val data = FakeAiData()
            val model = AiSettingsScreenModel(data, scope, UnsavedSetting(), scope)
            val revision = model.state.value.modelsRevision

            model.saveModelChoice { it.copy(imageModel = "image/next") }
            assertEquals("image/next", model.state.value.settings.imageModel)
            assertEquals(revision + 1, model.state.value.modelsRevision)

            model.setCoverEvidenceChapters(12)
            runCurrent()
            assertEquals(12, data.settings.coverEvidenceChapters)
        }

    // Settings hub

    @Test
    fun theHubShowsCachedValuesFirstThenFreshOnesAndFollowsCompression() =
        runTest(dispatcher) {
            val data = FakeHubData()
            val cache = SettingsHubCache().apply { summaries = HubSummaries("old voice", "Signed in", "Add an OpenRouter key") }
            val model = SettingsScreenModel(data, cache, scope)
            assertEquals(
                "old voice",
                model.state.value.summaries
                    ?.voice,
            )
            assertNull(model.state.value.phone)

            val render = launch { model.state.collect() }
            model.refresh()
            runCurrent()
            assertEquals(
                "Kokoro · Heart · 1.0×",
                model.state.value.summaries
                    ?.voice,
            )
            assertEquals(
                3,
                model.state.value.phone
                    ?.novels,
            )
            assertEquals(model.state.value.summaries, cache.summaries)

            val buildKey = model.state.value.buildKey
            data.audio = 700L
            data.compression.value = NarrationCompressionStatus(remainingChapters = 4)
            runCurrent()
            assertEquals(
                700L,
                model.state.value.phone
                    ?.audioBytes,
            )
            assertEquals(
                4,
                model.state.value.compression
                    ?.remainingChapters,
            )
            assertEquals("the card follows in place", buildKey, model.state.value.buildKey)
            render.cancel()
        }

    @Test
    fun theLayoutModeSavesAndChangesTheRows() =
        runTest(dispatcher) {
            val data = FakeHubData()
            val model = SettingsScreenModel(data, SettingsHubCache(), scope)
            model.setScreenLayoutMode("inner")
            runCurrent()
            assertEquals("inner", model.state.value.rows.screenLayoutMode)
        }
}

private class FakeDownloadData : DownloadSettingsData {
    var settings = AppSettings(maxParallelSources = 2)
    var overrides: Map<String, SourceDownloadSettings> = emptyMap()
    val saved = mutableListOf<AppSettings>()

    override fun settings(): AppSettings = settings

    override fun overrides(): Map<String, SourceDownloadSettings> = overrides

    override suspend fun saveSettings(settings: AppSettings) {
        saved += settings
        this.settings = settings
    }

    override suspend fun saveOverrides(overrides: Map<String, SourceDownloadSettings>) {
        this.overrides = overrides
    }
}

private class FakeTabsData : TabsData {
    var tabs: List<Tab> = emptyList()
    var counts: Map<String, Int> = emptyMap()
    val unassigned = mutableListOf<String>()
    private var ids = 0

    override fun tabs(): List<Tab> = tabs

    override fun novelCounts(): Map<String, Int> = counts

    override suspend fun saveTabs(tabs: List<Tab>) {
        this.tabs = tabs
    }

    override suspend fun unassign(tabId: String) {
        unassigned += tabId
    }

    override fun newTabId(): String = "tab_${++ids}"

    override fun now(): Long = 1L
}

private class FakeAiData : AiSettingsData {
    var settings = AiSettings()
    var saves = 0
    var usageCalls = 0
    var failUsage = false

    override fun settings(): AiSettings = settings

    override suspend fun save(settings: AiSettings) {
        saves++
        this.settings = settings
    }

    override fun ledger(): AiUsageLedger = AiUsageLedger()

    override suspend fun keyUsage(apiKey: String): OpenRouterKeyUsage {
        usageCalls++
        if (failUsage) error("offline")
        return OpenRouterKeyUsage("1.25", "0.5", null, "1.0", null, null, null)
    }

    override fun now(): Long = 0L
}

private class FakeHubData : SettingsHubData {
    var audio = 1_000L
    private var layoutMode = "auto"
    override val compression = MutableStateFlow(NarrationCompressionStatus())

    override suspend fun summaries(): HubSummaries = HubSummaries("Kokoro · Heart · 1.0×", "Not signed in", "Add an OpenRouter key")

    override suspend fun phoneSummary(): PhoneSummary = PhoneSummary(3, 40, audio, null, null)

    override suspend fun audioBytes(): Long = audio

    override fun rows(): SettingsHubRows =
        SettingsHubRows(
            cleanup = "2 rules",
            tabs = "No tabs",
            downloads = "Balanced",
            screenLayoutMode = layoutMode,
            notificationsOff = null,
            storageNeedsAttention = false,
            appUpdate = "Version 1.0",
            appUpdateNeedsAttention = false,
        )

    override suspend fun saveScreenLayoutMode(mode: String) {
        layoutMode = mode
    }
}
