package com.vinicius741.webnovelarchiver.core

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class StorySyncEngine(private val storage: AppStorage, private val network: NetworkClient) {
    suspend fun fetchOrSync(url: String, tabId: String? = null, status: (String) -> Unit = {}): Story {
        val provider = SourceRegistry.getProvider(url) ?: error("Unsupported source URL")
        if (!SourceUrlValidation.isImportableStoryUrl(url)) error("Unsupported source URL")
        val storyId = provider.getStoryId(url)
        val existing = storage.getStory(storyId)
        status("Fetching from ${provider.name}...")
        val html = network.fetch(url)
        val metadata = provider.parseMetadata(html)
        status("Parsing chapters...")
        val incoming = provider.getChapterList(html, url, network, status)
        if (incoming.isEmpty()) error("Source returned no chapters")
        val merge = StorySyncPlanning.mergeChapters(existing?.chapters ?: emptyList(), incoming, provider, existing?.lastReadChapterId)
        if (existing != null && merge.removedChapters.isNotEmpty()) createArchive(existing)
        val pendingNewChapterIds = if (existing == null) {
            null
        } else {
            StorySyncPlanning.buildPendingNewChapterIds(existing.pendingNewChapterIds, merge.newChapterIds, merge.chapters)
        }
        val story = Story(
            id = storyId,
            title = metadata.title,
            author = metadata.author,
            coverUrl = metadata.coverUrl ?: existing?.coverUrl,
            description = metadata.description,
            sourceUrl = metadata.canonicalUrl ?: url,
            status = if (existing == null) DownloadStatus.idle else if (merge.newChapterIds.isNotEmpty()) DownloadStatus.partial else existing.status,
            chapters = merge.chapters.toMutableList(),
            tags = metadata.tags,
            score = metadata.score,
            lastReadChapterId = merge.lastReadChapterId,
            epubPath = existing?.epubPath,
            epubPaths = existing?.epubPaths,
            epubStale = if (StorySyncPlanning.shouldMarkEpubStale(existing, merge.chapters.size)) true else existing?.epubStale,
            epubConfig = StorySyncPlanning.updateEpubConfigForSync(existing, merge.chapters.size),
            pendingNewChapterIds = pendingNewChapterIds,
            tabId = tabId ?: existing?.tabId,
            lastUpdated = System.currentTimeMillis(),
        )
        storage.addOrUpdateStory(story)
        return story
    }

    private fun createArchive(source: Story) {
        val now = System.currentTimeMillis()
        val archive = ArchiveSnapshotPlanning.buildArchiveSnapshot(source, now) { archiveId, index, chapter ->
            storage.copyChapterToStory(archiveId, index, chapter)
        }
        storage.addOrUpdateStory(archive)
    }

}

class DownloadEngine(private val storage: AppStorage, private val network: NetworkClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private val nextAllowedJobAtBySource = mutableMapOf<String, Long>()
    private var worker: Job? = null
    var onChanged: (() -> Unit)? = null
    var onProgress: ((DownloadProgress) -> Unit)? = null

    fun queue(story: Story, indexes: List<Int>, startNow: Boolean = true) {
        val plan = DownloadQueuePlanning.queueChapters(storage.getQueue(), story, indexes)
        if (plan.changed) {
            story.status = DownloadStatus.downloading
            story.lastUpdated = System.currentTimeMillis()
            storage.addOrUpdateStory(story)
            storage.saveQueue(plan.jobs)
        }
        if (startNow && plan.hasRunnableWork) start()
        onChanged?.invoke()
    }

    fun pauseAll() {
        storage.saveQueue(DownloadQueueControlPlanning.pauseAll(storage.getQueue()))
        onChanged?.invoke()
    }

    fun pauseJob(jobId: String) {
        storage.saveQueue(DownloadQueueControlPlanning.pauseJob(storage.getQueue(), jobId))
        onChanged?.invoke()
    }

    fun resumeJob(jobId: String) {
        storage.saveQueue(DownloadQueueControlPlanning.resumeJob(storage.getQueue(), jobId))
        start()
        onChanged?.invoke()
    }

    fun cancelAll() {
        storage.saveQueue(DownloadQueueControlPlanning.cancelAll(storage.getQueue()))
        onChanged?.invoke()
    }

    fun cancelJob(jobId: String) {
        storage.saveQueue(DownloadQueueControlPlanning.cancelJob(storage.getQueue(), jobId))
        onChanged?.invoke()
    }

    fun resumeAll() {
        storage.saveQueue(DownloadQueueControlPlanning.resumeAll(storage.getQueue()))
        start()
        onChanged?.invoke()
    }

    fun clearFinished() {
        storage.saveQueue(storage.getQueue().filterNot { it.status in setOf("completed", "failed", "cancelled") })
        onChanged?.invoke()
    }

    fun removeJob(jobId: String) {
        storage.saveQueue(storage.getQueue().filterNot { it.id == jobId })
        onChanged?.invoke()
    }

    fun retryFailed() {
        storage.saveQueue(DownloadQueueControlPlanning.retryFailed(storage.getQueue()))
        start()
        onChanged?.invoke()
    }

    fun retryJob(jobId: String) {
        storage.saveQueue(DownloadQueueControlPlanning.retryFailedJob(storage.getQueue(), jobId))
        start()
        onChanged?.invoke()
    }

    fun retryFailedForStory(storyId: String) {
        storage.saveQueue(DownloadQueueControlPlanning.retryFailed(storage.getQueue(), storyId))
        start()
        onChanged?.invoke()
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        worker = scope.launch {
            try {
                processLoop()
            } finally {
                running.set(false)
                onChanged?.invoke()
            }
        }
    }

    private suspend fun processLoop() {
        val settings = storage.getSettings()
        while (true) {
            cleanupUnsupportedSourceJobs()
            val queue = storage.getQueue()
            emitProgress(null)
            val sourceSettings = storage.getSourceDownloadSettings()
            val pending = DownloadScheduler.selectEligibleJobs(
                jobs = queue,
                now = System.currentTimeMillis(),
                globalConcurrency = settings.downloadConcurrency,
                globalDelay = settings.downloadDelay,
                sourceSettings = sourceSettings,
                activeCounts = emptyMap(),
                nextAllowedAt = nextAllowedJobAtBySource,
                providerNameForJob = { job -> SourceRegistry.getProvider(job.chapter.url)?.name },
            )
            if (pending.isEmpty()) {
                val sleepUntil = DownloadScheduler.nextWakeUpAt(
                    jobs = queue,
                    now = System.currentTimeMillis(),
                    nextAllowedAt = nextAllowedJobAtBySource,
                    providerNameForJob = { job -> SourceRegistry.getProvider(job.chapter.url)?.name },
                )
                if (sleepUntil != null) {
                    delay((sleepUntil - System.currentTimeMillis()).coerceAtLeast(200))
                    continue
                }
                break
            }
            pending.forEach { it.status = "downloading" }
            storage.saveQueue(queue)
            emitProgress(null)
            pending.map { job -> scope.launch { processJob(job) } }.forEach { it.join() }
            val now = System.currentTimeMillis()
            pending
                .mapNotNull { job -> SourceRegistry.getProvider(job.chapter.url)?.name }
                .distinct()
                .forEach { providerName ->
                    val sourceDelay = DownloadScheduler.settingsFor(providerName, settings.downloadConcurrency, settings.downloadDelay, sourceSettings).delay
                    if (sourceDelay > 0) nextAllowedJobAtBySource[providerName] = now + sourceDelay
                }
        }
    }

    private fun cleanupUnsupportedSourceJobs() {
        val queue = storage.getQueue()
        val cleanup = DownloadQueueMaintenance.failUnsupportedSourceJobs(queue) { job ->
            SourceRegistry.getProvider(job.chapter.url)?.name
        }
        if (cleanup.cleanedJobCount == 0) return
        storage.saveQueue(queue)
        cleanup.affectedStoryIds.forEach { storyId ->
            val story = storage.getStory(storyId) ?: return@forEach
            if (DownloadQueueMaintenance.recoverStuckDownloadingStory(story, queue.filter { it.storyId == storyId })) {
                storage.addOrUpdateStory(story)
            }
        }
        onChanged?.invoke()
    }

    private suspend fun processJob(job: DownloadJob) {
        try {
            emitProgress(job)
            val story = storage.getStory(job.storyId) ?: error("Story not found")
            val provider = SourceRegistry.getProvider(job.chapter.url) ?: error("Unsupported source")
            val html = network.fetch(job.chapter.url)
            val clean = TextCleanup.applyDownloadCleanup(provider.parseChapterContent(html), storage.getSentenceRemovalList(), storage.getRegexRules())
            val path = storage.saveChapter(story.id, job.chapterIndex, job.chapter, clean)
            val chapter = story.chapters[job.chapterIndex]
            chapter.filePath = path
            chapter.downloaded = true
            story.downloadedChapters = story.chapters.count { it.downloaded }
            story.status = if (story.downloadedChapters == story.chapters.size) DownloadStatus.completed else DownloadStatus.partial
            story.epubStale = true
            story.pendingNewChapterIds = story.pendingNewChapterIds?.filterNot { it == chapter.id }?.toMutableList()?.ifEmpty { null }
            story.lastUpdated = System.currentTimeMillis()
            storage.addOrUpdateStory(story)
            if (!isCancelled(job.id)) updateJob(job.id, "completed", null)
        } catch (error: Throwable) {
            if (!isCancelled(job.id)) handleJobError(job, error)
        }
    }

    private fun updateJob(id: String, status: String, error: String?) {
        val queue = storage.getQueue()
        queue.find { it.id == id }?.let {
            it.status = status
            it.error = error
            if (status == "completed") {
                it.errorCategory = null
                it.errorCode = null
                it.nextRetryAt = null
            }
        }
        storage.saveQueue(queue)
        emitProgress(queue.find { it.id == id })
        onChanged?.invoke()
    }

    private fun handleJobError(job: DownloadJob, error: Throwable) {
        val classified = DownloadErrorClassifier.classify(error)
        val queue = storage.getQueue()
        queue.find { it.id == job.id }?.let {
            it.retryCount += 1
            it.error = classified.message
            it.errorCategory = classified.category
            it.errorCode = classified.code
            if (DownloadErrorClassifier.shouldAutoRetry(it, classified)) {
                it.status = "pending"
                it.nextRetryAt = System.currentTimeMillis() + DownloadErrorClassifier.retryDelayMs(it)
            } else {
                it.status = if (classified.category == "cancelled") "cancelled" else "failed"
                it.nextRetryAt = null
            }
        }
        storage.saveQueue(queue)
        emitProgress(queue.find { it.id == job.id })
        onChanged?.invoke()
    }

    private fun isCancelled(id: String): Boolean = storage.getQueue().any { it.id == id && it.status == "cancelled" }

    fun currentProgress(): DownloadProgress = buildProgress(null)

    private fun emitProgress(activeJob: DownloadJob?) {
        onProgress?.invoke(buildProgress(activeJob))
    }

    private fun buildProgress(activeJob: DownloadJob?): DownloadProgress {
        val jobs = storage.getQueue()
        return DownloadProgress(
            pending = jobs.count { it.status == "pending" },
            active = jobs.count { it.status == "downloading" },
            completed = jobs.count { it.status == "completed" },
            failed = jobs.count { it.status == "failed" },
            cancelled = jobs.count { it.status == "cancelled" },
            paused = jobs.count { it.status == "paused" },
            total = jobs.size,
            activeTitle = activeJob?.let { "${it.storyTitle}: ${it.chapter.title}" },
        )
    }
}

object DownloadScheduler {
    fun selectEligibleJobs(
        jobs: List<DownloadJob>,
        now: Long,
        globalConcurrency: Int,
        globalDelay: Long,
        sourceSettings: Map<String, SourceDownloadSettings>,
        activeCounts: Map<String, Int>,
        nextAllowedAt: Map<String, Long>,
        providerNameForJob: (DownloadJob) -> String?,
    ): List<DownloadJob> {
        val availableGlobalSlots = globalConcurrency.coerceAtLeast(1) - activeCounts.values.sum()
        if (availableGlobalSlots <= 0) return emptyList()

        val selected = mutableListOf<DownloadJob>()
        val selectedBySource = mutableMapOf<String, Int>()
        for (job in jobs) {
            if (selected.size >= availableGlobalSlots) break
            if (job.status != "pending") continue
            if (job.nextRetryAt != null && job.nextRetryAt!! > now) continue

            val providerName = providerNameForJob(job) ?: continue
            val sourceLimit = settingsFor(providerName, globalConcurrency, globalDelay, sourceSettings).concurrency.coerceAtLeast(1)
            val activeForSource = activeCounts[providerName] ?: 0
            val selectedForSource = selectedBySource[providerName] ?: 0
            if (activeForSource + selectedForSource >= sourceLimit) continue
            if ((nextAllowedAt[providerName] ?: 0L) > now) continue

            selected.add(job)
            selectedBySource[providerName] = selectedForSource + 1
        }
        return selected
    }

    fun nextWakeUpAt(
        jobs: List<DownloadJob>,
        now: Long,
        nextAllowedAt: Map<String, Long>,
        providerNameForJob: (DownloadJob) -> String?,
    ): Long? {
        var next: Long? = null
        jobs.filter { it.status == "pending" }.forEach { job ->
            val retryAt = job.nextRetryAt?.takeIf { it > now }
            val providerAt = providerNameForJob(job)?.let { nextAllowedAt[it] }?.takeIf { it > now }
            listOfNotNull(retryAt, providerAt).forEach { candidate ->
                next = minOf(next ?: candidate, candidate)
            }
        }
        return next
    }

    fun settingsFor(
        providerName: String,
        globalConcurrency: Int,
        globalDelay: Long,
        sourceSettings: Map<String, SourceDownloadSettings>,
    ): SourceDownloadSettings {
        val override = sourceSettings[providerName]
        return SourceDownloadSettings(
            concurrency = override?.concurrency ?: globalConcurrency.coerceAtLeast(1),
            delay = override?.delay ?: globalDelay.coerceAtLeast(0),
        )
    }
}

data class ClassifiedDownloadError(
    val message: String,
    val category: String,
    val code: String,
    val retryable: Boolean,
)

object DownloadErrorClassifier {
    private const val RETRY_BASE_DELAY_MS = 3000L
    private const val RETRY_MAX_DELAY_MS = 60000L

    fun classify(error: Throwable): ClassifiedDownloadError {
        val message = error.message ?: "Download failed"
        val lower = message.lowercase()
        val httpCode = Regex("HTTP\\s+(\\d+)").find(message)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (httpCode != null) {
            val rateLimit = httpCode == 403 || httpCode == 429
            val retryableHttp = rateLimit || httpCode in setOf(408, 500, 502, 503, 504)
            return ClassifiedDownloadError("HTTP $httpCode", if (rateLimit) "rate_limit" else "network", httpCode.toString(), retryableHttp)
        }
        return when {
            lower.contains("cancel") || error is kotlinx.coroutines.CancellationException ->
                ClassifiedDownloadError("Cancelled", "cancelled", "CANCELLED", false)
            lower.contains("story not found") ->
                ClassifiedDownloadError(message, "missing_story", "STORY_NOT_FOUND", false)
            lower.contains("unsupported source") || lower.contains("no provider") ->
                ClassifiedDownloadError(message, "missing_provider", "NO_PROVIDER", false)
            lower.contains("no url") ->
                ClassifiedDownloadError(message, "invalid_chapter", "NO_CHAPTER_URL", false)
            lower.contains("empty") || lower.contains("too short") || lower.contains("no downloaded chapters") || lower.contains("content not found") ->
                ClassifiedDownloadError(message, "parse", "CONTENT_TOO_SHORT", true)
            lower.contains("network") || lower.contains("timeout") || lower.contains("failed to fetch") || lower.contains("connection") ->
                ClassifiedDownloadError(message, "network", "NETWORK_ERROR", true)
            else ->
                ClassifiedDownloadError(message, "unknown", error::class.java.simpleName.ifBlank { "UNKNOWN" }, false)
        }
    }

    fun shouldAutoRetry(job: DownloadJob, error: ClassifiedDownloadError): Boolean =
        error.retryable && job.retryCount <= job.maxRetries.coerceAtLeast(0)

    fun retryDelayMs(job: DownloadJob): Long {
        val retryAttempt = job.retryCount.coerceAtLeast(1)
        val multiplier = 1L shl (retryAttempt - 1).coerceIn(0, 10)
        return minOf(RETRY_MAX_DELAY_MS, RETRY_BASE_DELAY_MS * multiplier)
    }
}

data class DownloadProgress(
    val pending: Int,
    val active: Int,
    val completed: Int,
    val failed: Int,
    val cancelled: Int,
    val paused: Int,
    val total: Int,
    val activeTitle: String?,
) {
    val unfinished: Int
        get() = pending + active
}

object TextCleanup {
    private val regexFlagOrder = listOf('g', 'i', 'm', 's', 'u')
    private val allowedRegexFlags = regexFlagOrder.toSet()
    private val regexLiteral = Regex("^/((?:\\\\.|[^\\\\/])*)/([a-z]*)$", RegexOption.IGNORE_CASE)
    private val riskyRegexChecks = listOf(
        Regex("\\((?:[^()\\\\]|\\\\.)*[+*](?:[^()\\\\]|\\\\.)*\\)\\s*(?:\\+|\\*|\\{\\d*,?\\d*\\})"),
        Regex("\\((?:[^()\\\\]|\\\\.)*\\.\\*(?:[^()\\\\]|\\\\.)*\\)\\s*(?:\\+|\\*|\\{\\d*,?\\d*\\})"),
        Regex("\\((?:[^()\\\\]|\\\\.)*\\\\\\d+(?:[^()\\\\]|\\\\.)*\\)\\s*(?:\\+|\\*|\\{\\d*,?\\d*\\})"),
    )
    private const val maxRegexPatternLength = 500
    private const val maxRuleNameLength = 80

    data class RegexValidationResult(
        val valid: Boolean,
        val error: String? = null,
        val normalizedPattern: String? = null,
        val normalizedFlags: String? = null,
    )

    data class QuickPattern(
        val pattern: String,
        val flags: String,
        val name: String,
    )

    fun sanitizeRegexRules(rules: List<RegexCleanupRule>): MutableList<RegexCleanupRule> {
        val unique = linkedMapOf<String, RegexCleanupRule>()
        rules.forEach { rule ->
            val id = rule.id.trim()
            if (id.isBlank()) return@forEach
            val name = rule.name.trim()
            val validation = validateRegexRule(name, rule.pattern, rule.flags)
            if (!validation.valid) return@forEach
            val appliesTo = when (rule.appliesTo.trim().lowercase()) {
                "download", "tts", "both" -> rule.appliesTo.trim().lowercase()
                else -> "both"
            }
            unique[id] = rule.copy(
                id = id,
                name = name,
                pattern = validation.normalizedPattern ?: rule.pattern.trim(),
                flags = validation.normalizedFlags.orEmpty(),
                appliesTo = appliesTo,
            )
        }
        return unique.values.toMutableList()
    }

    fun hasSimilarRegexRule(
        rules: List<RegexCleanupRule>,
        currentId: String?,
        normalizedPattern: String,
        normalizedFlags: String,
        appliesTo: String,
    ): Boolean {
        val target = when (appliesTo.trim().lowercase()) {
            "download", "tts", "both" -> appliesTo.trim().lowercase()
            else -> "both"
        }
        return rules.any { rule ->
            rule.id != currentId &&
                rule.pattern == normalizedPattern &&
                rule.flags == normalizedFlags &&
                rule.appliesTo == target
        }
    }

    fun applyDownloadCleanup(html: String, sentences: List<String>, rules: List<RegexCleanupRule>): String {
        val doc = Jsoup.parseBodyFragment(html)
        val sentencePatterns = sentences.mapNotNull { sentence ->
            val escaped = Regex.escape(sentence.trim()).replace("\\ ", "\\s+")
            if (escaped.isBlank()) null else runCatching { Regex(escaped, setOf(RegexOption.IGNORE_CASE)) }.getOrNull()
        }
        val regexRules = rules.filter { it.enabled && (it.appliesTo == "both" || it.appliesTo == "download") }.mapNotNull {
            runCatching {
                Regex(it.pattern, regexOptions(it.flags))
            }.getOrNull()
        }
        doc.select("script,style,noscript,iframe").remove()
        doc.body().traverseTextNodes().forEach { node ->
            var text = node.text()
            sentencePatterns.forEach { text = it.replace(text, "") }
            regexRules.forEach { text = it.replace(text, "") }
            node.text(text)
        }
        return doc.body().html()
    }

    fun htmlToPlainText(html: String): String {
        val doc = Jsoup.parseBodyFragment(html)
        doc.select("script,style,noscript,iframe").remove()
        doc.select("p,div,br,h1,h2,h3,h4,h5,h6,li").append(" ")
        return doc.body().text().replace(Regex("\\s+"), " ").trim()
    }

    fun htmlToFormattedText(html: String): String {
        if (html.isBlank()) return ""
        val doc = Jsoup.parseBodyFragment(html)
        doc.select("script,style,noscript,iframe").remove()
        val lines = mutableListOf<String>()

        fun emit(text: String, major: Boolean = false) {
            val value = text.replace(Regex("[ \\t]+"), " ").trim()
            if (value.isBlank()) return
            if (major && lines.isNotEmpty() && lines.last().isNotBlank()) lines.add("")
            lines.add(value)
            if (major) lines.add("")
        }

        fun collectInline(element: org.jsoup.nodes.Element): String {
            val parts = mutableListOf<String>()
            element.childNodes().forEach { child ->
                when (child) {
                    is TextNode -> parts.add(child.text())
                    is org.jsoup.nodes.Element -> {
                        when (child.tagName().lowercase()) {
                            "br" -> parts.add("\n")
                            "script", "style", "noscript", "iframe" -> Unit
                            "td", "th" -> parts.add(collectInline(child))
                            else -> parts.add(collectInline(child))
                        }
                    }
                }
            }
            return parts.joinToString("")
                .replace(Regex("[ \\t]*\\n[ \\t]*"), "\n")
                .replace(Regex("[ \\t]+"), " ")
                .trim()
        }

        val blockTags = setOf("p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "blockquote", "table")

        fun walk(element: org.jsoup.nodes.Element) {
            when (element.tagName().lowercase()) {
                "table", "tbody", "thead" -> element.children().forEach(::walk)
                "tr" -> {
                    val row = element.children()
                        .filter { it.tagName().equals("td", true) || it.tagName().equals("th", true) }
                        .joinToString(" | ") { collectInline(it) }
                    emit(row)
                }
                "p", "li" -> emit(collectInline(element))
                "blockquote", "h1", "h2", "h3", "h4", "h5", "h6" -> emit(collectInline(element), major = true)
                "div" -> {
                    val blockChildren = element.children().filter { child ->
                        child.tagName().lowercase() in blockTags
                    }
                    if (blockChildren.isNotEmpty() && element.ownText().isBlank()) {
                        element.children().forEach(::walk)
                    } else {
                        emit(collectInline(element))
                    }
                }
                "ul", "ol" -> element.children().forEach(::walk)
                "body" -> {
                    val hasBlockChildren = element.children().any { it.tagName().lowercase() in blockTags }
                    if (hasBlockChildren) element.children().forEach(::walk) else emit(collectInline(element))
                }
                else -> {
                    if (element.children().isEmpty()) emit(collectInline(element)) else element.children().forEach(::walk)
                }
            }
        }

        walk(doc.body())
        return lines.joinToString("\n")
            .replace(Regex("[ \\t]+\\n"), "\n")
            .replace(Regex("\\n[ \\t]+"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trimEnd()
    }

    fun prepareTtsChunks(
        html: String,
        regexRules: List<RegexCleanupRule>,
        chunkSize: Int,
    ): List<String> {
        if (html.isBlank()) return emptyList()

        val doc = Jsoup.parseBodyFragment(html)
        doc.select("script,style,noscript,iframe").remove()
        val cleanupForDisplay = regexRunner(regexRules, "download")
        val cleanupForTts = regexRunner(regexRules, "tts")
        val chunks = mutableListOf<String>()
        var current = ""
        val effectiveChunkSize = chunkSize.coerceAtLeast(100)

        val elements = doc.select("p,h1,h2,h3,h4,h5,h6,li,blockquote,div")
        elements.forEach { element ->
            if (
                element.tagName().equals("div", ignoreCase = true) &&
                element.select("> p, > div, > h1, > h2, > h3, > h4, > h5, > h6, > ul, > ol, > blockquote").isNotEmpty()
            ) {
                return@forEach
            }

            val displayText = cleanupForDisplay(element.text()).replace(Regex("\\s+"), " ").trim()
            if (displayText.isBlank()) return@forEach

            val ttsText = cleanupForTts(displayText).replace(Regex("\\s+"), " ").trim()
            if (ttsText.isBlank()) return@forEach

            if (current.isNotBlank() && current.length + 1 + ttsText.length > effectiveChunkSize) {
                chunks.add(current)
                current = ""
            }
            current = if (current.isBlank()) ttsText else "$current $ttsText"
        }

        if (current.isNotBlank()) {
            chunks.add(current)
        }

        if (chunks.isNotEmpty()) return chunks

        val fallback = cleanupForTts(cleanupForDisplay(doc.body().text()))
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (fallback.isBlank()) emptyList() else fallback.chunked(effectiveChunkSize)
    }

    fun validateRegexRule(name: String, patternInput: String, flagsInput: String): RegexValidationResult {
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) return RegexValidationResult(false, "Rule name is required.")
        if (trimmedName.length > maxRuleNameLength) {
            return RegexValidationResult(false, "Rule name must be $maxRuleNameLength characters or fewer.")
        }

        val normalizedInput = normalizeRegexInput(patternInput, flagsInput)
        normalizedInput.error?.let { return RegexValidationResult(false, it) }
        val pattern = normalizedInput.pattern
        val flags = normalizedInput.flags

        if (pattern.isBlank()) return RegexValidationResult(false, "Regex pattern is required.")
        if (pattern.length > maxRegexPatternLength) {
            return RegexValidationResult(false, "Regex pattern must be $maxRegexPatternLength characters or fewer.")
        }
        flags.forEach { flag ->
            if (flag !in allowedRegexFlags) {
                return RegexValidationResult(false, "Unsupported regex flag: \"$flag\". Allowed flags: ${regexFlagOrder.joinToString("")}")
            }
        }
        if (riskyRegexChecks.any { it.containsMatchIn(pattern) }) {
            return RegexValidationResult(false, "Unsafe regex pattern: nested quantified groups can cause very slow matching.")
        }

        val normalizedFlags = normalizeRegexFlags(flags)
        return runCatching {
            Regex(pattern, regexOptions(normalizedFlags.replace("g", "")))
            RegexValidationResult(true, normalizedPattern = pattern, normalizedFlags = normalizedFlags)
        }.getOrElse { RegexValidationResult(false, "Invalid regex: ${it.message}") }
    }

    fun generateQuickPattern(characters: String, minCount: Int, wholeLine: Boolean): QuickPattern? {
        val value = characters.trim()
        if (value.isBlank() || minCount < 1) return null
        val escaped = escapeRegexLiteral(value)
        val core = if (value.length > 1) "(?:$escaped){$minCount,}" else "$escaped{$minCount,}"
        val pattern = if (wholeLine) "^[\\s]*$core[\\s]*$" else core
        val flags = if (wholeLine) "gm" else "g"
        val display = if (value.length > 4) "${value.take(4)}..." else value
        val scope = if (wholeLine) "separator lines" else "patterns"
        return QuickPattern(pattern, flags, "Remove $display ($minCount+) $scope")
    }

    private fun regexRunner(rules: List<RegexCleanupRule>, target: String): (String) -> String {
        val compiled = rules
            .filter { it.enabled && (it.appliesTo == "both" || it.appliesTo == target) }
            .mapNotNull { rule ->
                runCatching {
                    Regex(rule.pattern, regexOptions(rule.flags))
                }.getOrNull()
            }
        return { input -> compiled.fold(input) { text, regex -> regex.replace(text, "") } }
    }

    private data class NormalizedRegexInput(val pattern: String, val flags: String, val error: String? = null)

    private fun normalizeRegexInput(patternInput: String, flagsInput: String): NormalizedRegexInput {
        val trimmedPattern = patternInput.trim()
        val trimmedFlags = flagsInput.trim().lowercase()
        val maybeLiteral = trimmedPattern.startsWith("/") && trimmedPattern.lastIndexOf("/") > 0
        val match = regexLiteral.matchEntire(trimmedPattern)

        if (match == null && maybeLiteral) {
            return NormalizedRegexInput(trimmedPattern, trimmedFlags, "Invalid regex literal. Use /pattern/flags or provide pattern and flags separately.")
        }
        if (match == null) return NormalizedRegexInput(trimmedPattern, trimmedFlags)

        return NormalizedRegexInput(match.groupValues[1], trimmedFlags + match.groupValues[2].lowercase())
    }

    private fun normalizeRegexFlags(flags: String): String {
        val unique = flags.trim().lowercase().toSet()
        return regexFlagOrder.filter { it in unique }.joinToString("")
    }

    private fun regexOptions(flags: String): Set<RegexOption> {
        val opts = mutableSetOf<RegexOption>()
        if ('i' in flags) opts.add(RegexOption.IGNORE_CASE)
        if ('m' in flags) opts.add(RegexOption.MULTILINE)
        if ('s' in flags) opts.add(RegexOption.DOT_MATCHES_ALL)
        return opts
    }

    private fun escapeRegexLiteral(value: String): String {
        val special = setOf('.', '*', '+', '?', '^', '$', '{', '}', '(', ')', '|', '[', ']', '\\', '-')
        return buildString {
            value.forEach { char ->
                if (char in special) append('\\')
                append(char)
            }
        }
    }

    private fun Node.traverseTextNodes(): List<TextNode> {
        val result = mutableListOf<TextNode>()
        fun visit(node: Node) {
            if (node is TextNode) {
                result.add(node)
            }
            node.childNodes().forEach(::visit)
        }
        visit(this)
        return result
    }
}

class EpubEngine(private val storage: AppStorage, private val network: NetworkClient) {
    private data class CoverAsset(val data: ByteArray, val href: String, val mediaType: String)

    suspend fun generate(
        story: Story,
        chapters: List<Chapter>,
        maxPerFile: Int,
        originalChapterNumbers: List<Int>? = null,
        progress: (String) -> Unit = {},
    ): List<EpubResult> = withContext(Dispatchers.IO) {
        val available = chapters.filter { it.content != null || it.filePath?.let { path -> File(path).exists() } == true }
        if (available.isEmpty()) error("No downloaded chapters available")
        val chaptersPerFile = maxPerFile.coerceIn(
            SettingsValidation.MAX_CHAPTERS_PER_EPUB_MIN,
            SettingsValidation.MAX_CHAPTERS_PER_EPUB_MAX,
        )
        val chunks = available.chunked(chaptersPerFile)
        val results = mutableListOf<EpubResult>()
        val chapterNumberById = chapters.mapIndexed { index, chapter ->
            chapter.id to (originalChapterNumbers?.getOrNull(index) ?: (index + 1))
        }.toMap()
        chunks.forEachIndexed { index, chunk ->
            progress("Generating EPUB ${index + 1}/${chunks.size}...")
            val start = chapterNumberById[chunk.first().id] ?: (chapters.indexOf(chunk.first()) + 1)
            val end = chapterNumberById[chunk.last().id] ?: (chapters.indexOf(chunk.last()) + 1)
            val filename = EpubFilename.forRange(story.title, start, end)
            val bytes = buildEpub(story, chunk)
            val file = storage.saveEpub(story.id, filename, bytes)
            results.add(EpubResult(file.absolutePath, filename, start, end))
        }
        story.epubPaths = results.map { it.uri }.toMutableList()
        story.epubPath = results.firstOrNull()?.uri
        story.epubStale = false
        storage.addOrUpdateStory(story)
        results
    }

    private suspend fun buildEpub(story: Story, chapters: List<Chapter>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            ArchiveUtils.putStoredEntry(zip, "mimetype", "application/epub+zip".toByteArray())
            entry(zip, "META-INF/container.xml", """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            entry(zip, "OEBPS/style.css", EpubContent.css())
            val coverAsset = story.coverUrl?.let { fetchCover(it) }
            coverAsset?.let { cover ->
                zip.putNextEntry(ZipEntry("OEBPS/${cover.href}"))
                zip.write(cover.data)
                zip.closeEntry()
            }
            entry(zip, "OEBPS/cover.xhtml", EpubContent.cover(story, coverAsset?.href))
            entry(zip, "OEBPS/details.xhtml", EpubContent.details(story))
            entry(zip, "OEBPS/toc.xhtml", EpubContent.tableOfContents(chapters))
            chapters.forEachIndexed { i, chapter ->
                entry(zip, "OEBPS/chapter_${i + 1}.xhtml", EpubContent.chapter(chapter, storage.readChapter(chapter) ?: ""))
            }
            entry(zip, "OEBPS/content.opf", EpubMetadata.opf(story, chapters, coverAsset?.let { EpubCoverMetadata(it.href, it.mediaType) }))
            entry(zip, "OEBPS/toc.ncx", EpubMetadata.ncx(story, chapters))
        }
        return out.toByteArray()
    }

    private suspend fun fetchCover(url: String): CoverAsset? = runCatching {
        withContext(Dispatchers.IO) {
            val connection = URL(url).openConnection()
            try {
                if (connection is HttpURLConnection && connection.responseCode !in 200..299) {
                    return@withContext null
                }
                val mediaType = normalizeCoverMediaType(connection.contentType, url)
                if (!mediaType.startsWith("image/")) return@withContext null
                val extension = getCoverExtension(url, mediaType)
                val data = connection.getInputStream().use { it.readBytes() }
                CoverAsset(data, "images/cover.$extension", mediaType)
            } finally {
                if (connection is HttpURLConnection) connection.disconnect()
            }
        }
    }.getOrNull()

    private fun entry(zip: ZipOutputStream, path: String, text: String) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(text.toByteArray())
        zip.closeEntry()
    }

    private fun xml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
    private fun normalizeCoverMediaType(contentType: String?, coverUrl: String): String = contentType
        ?.substringBefore(";")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: getCoverMediaType(coverUrl)

    private fun getCoverMediaType(coverUrl: String): String {
        val extension = getExtensionFromUrl(coverUrl)
        return coverMediaTypes[extension] ?: "image/jpeg"
    }

    private fun getCoverExtension(coverUrl: String, mediaType: String): String {
        val extension = getExtensionFromUrl(coverUrl)
        if (extension != null && coverMediaTypes[extension] == mediaType) return extension
        val matched = coverMediaTypes.entries.firstOrNull { it.value == mediaType }?.key
        return if (matched == "jpeg") "jpg" else matched ?: "jpg"
    }

    private fun getExtensionFromUrl(url: String): String? = runCatching {
        URL(url).path.substringAfterLast('.', missingDelimiterValue = "").lowercase().takeIf { it.isNotBlank() }
    }.getOrElse {
        url.substringBefore('?').substringAfterLast('.', missingDelimiterValue = "").lowercase().takeIf { it.isNotBlank() }
    }

    private val coverMediaTypes = mapOf(
        "gif" to "image/gif",
        "jpeg" to "image/jpeg",
        "jpg" to "image/jpeg",
        "png" to "image/png",
        "svg" to "image/svg+xml",
        "webp" to "image/webp",
    )
}

class TtsEngine(private val context: Context, private val storage: AppStorage) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = TextToSpeech(context, this)
    private var chunks: List<String> = emptyList()
    private var index = 0
    private var session: TtsSession? = null
    private var playbackActive = false

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val settings = storage.getTtsSettings()
            applySettings(settings)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onError(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) {
                    handleChunkDone()
                }
            })
        }
    }

    fun play(story: Story, chapter: Chapter) {
        val settings = storage.getTtsSettings()
        applySettings(settings)
        val html = storage.readChapter(chapter) ?: chapter.content ?: ""
        chunks = TextCleanup.prepareTtsChunks(html, storage.getRegexRules(), settings.chunkSize)
        if (chunks.isEmpty()) return
        startSession(story, chapter, settings, 0)
        playbackActive = true
        speakCurrent()
    }

    fun resumePersistedSession(): Boolean {
        val persisted = storage.getTtsSession() ?: return false
        if (!TtsSessionPlanning.isResumeEligible(persisted)) return false
        val story = storage.getStory(persisted.storyId) ?: return false
        val chapter = story.chapters.firstOrNull { it.id == persisted.chapterId } ?: return false
        val settings = storage.getTtsSettings()
        applySettings(settings)
        val html = storage.readChapter(chapter) ?: chapter.content ?: return false
        chunks = TextCleanup.prepareTtsChunks(html, storage.getRegexRules(), TtsSessionPlanning.restoredChunkSize(settings))
        if (chunks.isEmpty()) return false
        val startIndex = TtsSessionPlanning.boundedChunkIndex(persisted, chunks.size)
        startSession(story, chapter, settings, startIndex)
        playbackActive = true
        speakCurrent()
        return true
    }

    fun next() {
        if (chunks.isEmpty()) return
        playbackActive = false
        tts?.stop()
        playbackActive = true
        index = index.coerceAtMost(chunks.lastIndex)
        speakCurrent()
    }

    fun previous() {
        if (chunks.isEmpty()) return
        playbackActive = false
        tts?.stop()
        playbackActive = true
        index = (index - 2).coerceAtLeast(0)
        speakCurrent()
    }

    fun pause() {
        playbackActive = false
        tts?.stop()
        session?.let {
            it.isPaused = true
            it.wasPlaying = false
            it.currentChunkIndex = (index - 1).coerceAtLeast(0)
            storage.saveTtsSession(it)
        }
    }

    fun stop() {
        playbackActive = false
        tts?.stop()
        storage.clearTtsSession()
    }

    private fun startSession(story: Story, chapter: Chapter, settings: TtsSettings, startIndex: Int) {
        index = startIndex
        session = TtsSession(
            storyId = story.id,
            chapterId = chapter.id,
            chapterTitle = chapter.title,
            currentChunkIndex = startIndex,
            isPaused = false,
            wasPlaying = true,
            chunkSize = settings.chunkSize,
            voiceIdentifier = settings.voiceIdentifier,
            rate = settings.rate,
            pitch = settings.pitch,
        )
    }

    fun availableVoices(): List<VoiceInfo> {
        return tts?.voices
            ?.filter { !it.isNetworkConnectionRequired }
            ?.sortedWith(compareBy<Voice> { it.locale.toLanguageTag() }.thenBy { it.name })
            ?.map {
                VoiceInfo(
                    identifier = it.name,
                    name = it.name,
                    language = it.locale.toLanguageTag(),
                    quality = it.quality,
                    latency = it.latency,
                )
            }
            ?: emptyList()
    }

    private fun applySettings(settings: TtsSettings) {
        val engine = tts ?: return
        val selectedVoice = settings.voiceIdentifier?.let { id -> engine.voices?.firstOrNull { it.name == id } }
        if (selectedVoice != null) {
            engine.voice = selectedVoice
        } else {
            engine.language = Locale.getDefault()
        }
        engine.setPitch(settings.pitch)
        engine.setSpeechRate(settings.rate)
    }

    private fun speakCurrent() {
        val current = chunks.getOrNull(index) ?: run {
            playbackActive = false
            storage.clearTtsSession()
            return
        }
        session?.let { storage.saveTtsSession(it.copy(currentChunkIndex = index, isPaused = false, wasPlaying = true, updatedAt = System.currentTimeMillis())) }
        tts?.speak(current, TextToSpeech.QUEUE_FLUSH, null, "chapter_chunk_$index")
        index += 1
    }

    private fun handleChunkDone() {
        if (!playbackActive) return
        if (index < chunks.size) {
            speakCurrent()
            return
        }
        handleChapterFinished()
    }

    private fun handleChapterFinished() {
        val currentSession = session ?: run {
            playbackActive = false
            storage.clearTtsSession()
            return
        }
        val story = storage.getStory(currentSession.storyId) ?: run {
            playbackActive = false
            storage.clearTtsSession()
            return
        }
        story.lastReadChapterId = currentSession.chapterId
        storage.addOrUpdateStory(story)

        val nextIndex = TtsSessionPlanning.nextChapterIndex(story, currentSession.chapterId) ?: run {
            playbackActive = false
            storage.clearTtsSession()
            return
        }
        val nextChapter = story.chapters[nextIndex]
        val settings = storage.getTtsSettings()
        applySettings(settings)
        val html = storage.readChapter(nextChapter) ?: nextChapter.content
        val nextChunks = html?.let { TextCleanup.prepareTtsChunks(it, storage.getRegexRules(), settings.chunkSize) }.orEmpty()
        if (nextChunks.isEmpty()) {
            playbackActive = false
            storage.clearTtsSession()
            return
        }
        chunks = nextChunks
        startSession(story, nextChapter, settings, 0)
        playbackActive = true
        speakCurrent()
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
    }
}

data class VoiceInfo(
    val identifier: String,
    val name: String,
    val language: String,
    val quality: Int,
    val latency: Int,
)
