package com.vinicius741.webnovelarchiver.simulation

import android.app.Activity
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.vinicius741.webnovelarchiver.ai.AiCoverJobEvent
import com.vinicius741.webnovelarchiver.ai.AiJobForegroundService
import com.vinicius741.webnovelarchiver.app.AppContainer
import com.vinicius741.webnovelarchiver.data.repository.getAiUsageLedger
import com.vinicius741.webnovelarchiver.data.repository.loadAiCoverDraft
import com.vinicius741.webnovelarchiver.data.repository.setAiCover
import com.vinicius741.webnovelarchiver.download.DownloadEngine
import com.vinicius741.webnovelarchiver.download.DownloadForegroundService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

/** Optional automation drives production engines; --manual leaves every action to the UI. */
internal class SimulationActions(
    private val config: SimulationConfig,
    private val journal: SimulationJournal,
    private val image: ByteArray,
    private val replayStoryUrls: List<String> = emptyList(),
) {
    private val additionalAssertions = mutableMapOf<String, Boolean>()

    suspend fun run(
        activity: Activity,
        container: AppContainer,
    ) {
        val started = System.currentTimeMillis()
        try {
            val action = config.scenario.action
            if (action == "composite") {
                coroutineScope {
                    val cover = async { runAi(activity, container, cover = true) }
                    queueDownloads(activity, container)
                    monitorDownloads(container)
                    cover.await()
                }
            } else {
                when (action) {
                    "cover", "description" -> runAi(activity, container, action == "cover")
                    "downloads" -> {
                        queueDownloads(activity, container)
                        monitorDownloads(container)
                    }
                    "import" -> container.syncEngine.fetchOrSync(SimulationFixtures.STORY_URL, refreshPatreonStats = false)
                    "sources" -> {
                        replayStoryUrls.forEach { container.syncEngine.fetchOrSync(it, refreshPatreonStats = false) }
                        queueDownloads(activity, container)
                        monitorDownloads(container)
                    }
                    "patreon" ->
                        check(
                            com.vinicius741.webnovelarchiver.source
                                .PatreonApi(container.network)
                                .currentUser()
                                .name == "Simulation reader",
                        )
                    "protocols" -> {
                        container.network.postForm(
                            "https://www.scribblehub.com/wp-admin/admin-ajax.php",
                            mapOf("action" to "wi_getreleases_pagination", "pagenum" to 1, "mypostid" to "98765"),
                        )
                        check(
                            com.vinicius741.webnovelarchiver.source
                                .PatreonApi(container.network)
                                .currentUser()
                                .name == "Simulation reader",
                        )
                    }
                    "probe" -> runCatching { container.network.fetch("https://example.invalid/unmatched") }
                    "none" -> {
                        check(container.openRouter.fetchModels().isNotEmpty())
                        check(container.openRouter.fetchDecisionModels().isNotEmpty())
                        check(container.openRouter.fetchImageModels().isNotEmpty())
                        check(container.openRouter.fetchCurrentKeyUsage("simulation-dummy-key").usage != null)
                    }
                    else -> error("Unknown simulation action")
                }
            }
            journal.summary(summary(container).apply { addProperty("actualDurationMillis", System.currentTimeMillis() - started) })
            finish(container)
        } catch (error: CancellationException) {
            journal.phase("interrupted")
            throw error
        } catch (error: Exception) {
            // Deliberately report only the exception type, never provider bodies or prompts.
            journal.event("action_failed", config.scenario.action, error.javaClass.simpleName)
            journal.summary(summary(container))
            journal.assertions(
                mapOf(
                    "expectedFailureObserved" to (
                        config.scenario.expectedFailure &&
                            (journal.faultObserved() || config.scenario.missingKey || config.scenario.archived)
                    ),
                    "noUnexpectedRequests" to (journal.deniedCount() == config.scenario.expectedDenials),
                ),
            )
        }
    }

    suspend fun resumeDownloads(
        activity: Activity,
        container: AppContainer,
    ) {
        withContext(Dispatchers.Main) { DownloadForegroundService.start(activity) }
        container.applicationScope.async {
            journal.phase("running")
            monitorDownloads(container)
            finish(container)
        }
    }

    private suspend fun runAi(
        activity: Activity,
        container: AppContainer,
        cover: Boolean,
    ) {
        repeat(config.scenario.repeat) { iteration ->
            val selectionsBefore =
                container.repository
                    .getAiUsageLedger()
                    .recentRecords
                    .count { it.feature.endsWith("_selection") }
            if (cover) {
                runCover(activity, container)
            } else {
                val draft =
                    container.aiDescriptionEngine.draft(SimulationFixtures.STORY_ID) { message ->
                        journal.event("progress", "description", progressStage(message))
                    }
                container.repository.setAiDescription(SimulationFixtures.STORY_ID, draft)
            }
            if (iteration > 0 && !config.scenario.manual) {
                val selectionsAfter =
                    container.repository
                        .getAiUsageLedger()
                        .recentRecords
                        .count { it.feature.endsWith("_selection") }
                additionalAssertions["evidenceCacheReused"] = selectionsAfter == selectionsBefore
            }
        }
    }

    private suspend fun runCover(
        activity: Activity,
        container: AppContainer,
    ) = coroutineScope {
        val result = async(start = CoroutineStart.UNDISPATCHED) { container.aiCoverJobCoordinator.events.first() }
        withContext(Dispatchers.Main) {
            AiJobForegroundService.start(activity, "Simulation cover generation")
            check(container.aiCoverJobCoordinator.startOneShot(SimulationFixtures.STORY_ID))
        }
        when (val event = result.await()) {
            is AiCoverJobEvent.Failed -> error("Simulated cover job failed")
            is AiCoverJobEvent.Succeeded -> {
                check(container.repository.loadAiCoverDraft(SimulationFixtures.STORY_ID) != null)
                if (config.scenario.repeat > 1) container.repository.setAiCover(SimulationFixtures.STORY_ID, image, "image/png")
                journal.event("persisted", "cover")
            }
        }
    }

    private suspend fun queueDownloads(
        activity: Activity,
        container: AppContainer,
    ) {
        val handle =
            DownloadEngine(container.repository, container.network, container.downloadPacer, ownsProcessLoop = false, time = container.time)
        try {
            container.repository.library().filter { it.isArchived != true }.forEach { story ->
                val indexes = story.chapters.indices.filter { !story.chapters[it].downloaded }
                handle.queue(story, indexes, startNow = false)
            }
            withContext(Dispatchers.Main) { DownloadForegroundService.start(activity) }
        } finally {
            handle.close()
        }
    }

    private suspend fun monitorDownloads(container: AppContainer) {
        withTimeout(240_000L) {
            while (true) {
                val jobs = container.repository.queue()
                val blocked = container.network.reliabilitySnapshots().any { it.manualVerificationRequired }
                if (blocked) {
                    journal.phase("waiting_verification")
                    if (config.scenario.expectedFailure &&
                        jobs.filter { it.sourceId == "fanfiction_net" }.all { it.status == "completed" }
                    ) {
                        break
                    }
                }
                if (jobs.isNotEmpty() && jobs.none { it.status in setOf("pending", "downloading") }) break
                delay(100)
            }
        }
    }

    private suspend fun finish(container: AppContainer) {
        val action = config.scenario.action
        val jobs = container.repository.queue()
        val assertions = mutableMapOf("noUnexpectedRequests" to (journal.deniedCount() == config.scenario.expectedDenials))
        if (action in setOf("downloads", "composite", "sources")) {
            val failures =
                jobs.any { it.status == "failed" } ||
                    container.network.reliabilitySnapshots().any { it.manualVerificationRequired }
            assertions["downloadOutcome"] = jobs.isNotEmpty() && failures == config.scenario.expectedFailure
            assertions["chapterFilesExist"] =
                container.repository
                    .library()
                    .flatMap { it.chapters }
                    .filter { it.downloaded }
                    .all { container.repository.resolveAbsolutePath(it.filePath)?.isFile == true }
            assertions["independentSourceCompleted"] =
                jobs
                    .filter { it.sourceId == "fanfiction_net" }
                    .let { it.isNotEmpty() && it.all { job -> job.status == "completed" } }
        }
        if (action == "sources") assertions["allSourcesImported"] = container.repository.library().size == replayStoryUrls.size
        if (config.scenario.faults["source"] == "429") {
            assertions["rateLimitObserved"] = container.network.reliabilitySnapshots().any { it.rateLimitCount > 0 }
        }
        if (action in setOf("description", "cover", "composite") && !config.scenario.expectedFailure) {
            val ledger = container.repository.getAiUsageLedger()
            assertions["receiptsRecorded"] = ledger.allTimeCallCount > 0
            assertions["receiptCosts"] =
                if (config.scenario.faults["receipt"] == "missing") {
                    ledger.unknownCallCount > 0
                } else {
                    ledger.allTimeCostUsd.toBigDecimal() > java.math.BigDecimal.ZERO
                }
        }
        if (action == "description") {
            assertions["descriptionSaved"] =
                !container.repository
                    .story(SimulationFixtures.STORY_ID)
                    ?.aiDescription
                    .isNullOrBlank()
        }
        if (action in setOf("cover", "composite")) {
            assertions["coverDraftSaved"] = container.repository.loadAiCoverDraft(SimulationFixtures.STORY_ID) != null
        }
        if (action in setOf("cover", "description") && config.scenario.expectedFailure) assertions["expectedFailureObserved"] = false
        assertions.putAll(additionalAssertions)
        journal.assertions(assertions)
    }

    private fun progressStage(message: String): String = if (message.startsWith("Writing")) "writing" else "selecting"

    companion object {
        fun summary(container: AppContainer): JsonObject =
            JsonObject().apply {
                val stories = container.repository.library()
                val queue = container.repository.queue()
                addProperty("storyCount", stories.size)
                addProperty("downloadedChapters", stories.sumOf { story -> story.chapters.count { it.downloaded } })
                addProperty("aiJobsRunning", container.aiCoverJobCoordinator.jobs.value.size)
                addProperty(
                    "usageRecords",
                    container.repository
                        .getAiUsageLedger()
                        .recentRecords.size,
                )
                add(
                    "jobs",
                    JsonArray().apply {
                        queue.forEach { job ->
                            add(
                                JsonObject().apply {
                                    addProperty("id", job.id)
                                    addProperty("source", job.sourceId)
                                    addProperty("status", job.status)
                                    addProperty("retryCount", job.retryCount)
                                    addProperty("errorCategory", job.errorCategory)
                                    addProperty("errorCode", job.errorCode)
                                    addProperty("nextRetryAt", job.nextRetryAt)
                                },
                            )
                        }
                    },
                )
                add(
                    "queue",
                    JsonObject().apply {
                        queue.groupingBy { it.status }.eachCount().forEach { (status, count) ->
                            addProperty(status, count)
                        }
                    },
                )
                add(
                    "sources",
                    JsonArray().apply {
                        container.network.reliabilitySnapshots().forEach { state ->
                            add(
                                JsonObject().apply {
                                    addProperty("host", state.host)
                                    addProperty("blocked", state.manualVerificationRequired)
                                    addProperty("browserTransport", state.browserTransportActive)
                                    addProperty("requests", state.requestCount)
                                    addProperty("challenges", state.challengeCount)
                                    addProperty("rateLimits", state.rateLimitCount)
                                    addProperty("cooldownRemainingMillis", state.cooldownRemainingMillis)
                                },
                            )
                        }
                    },
                )
            }
    }
}
