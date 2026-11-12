package com.vinicius741.webnovelarchiver.narration

import android.app.ActivityManager
import android.content.Context
import android.os.StatFs
import com.vinicius741.webnovelarchiver.cleanup.TtsTextPreparation
import com.vinicius741.webnovelarchiver.data.diagnostics.ActivityCategory
import com.vinicius741.webnovelarchiver.data.diagnostics.ActivityLog
import com.vinicius741.webnovelarchiver.data.repository.AppRepository
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.model.TtsSettings
import com.vinicius741.webnovelarchiver.tts.TtsContinueListening
import com.vinicius741.webnovelarchiver.tts.TtsEngineCatalog
import com.vinicius741.webnovelarchiver.tts.TtsNarrationPlanning
import com.vinicius741.webnovelarchiver.tts.kokoro.KokoroCpu
import com.vinicius741.webnovelarchiver.tts.kokoro.KokoroRuntime
import com.vinicius741.webnovelarchiver.tts.kokoro.KokoroVoices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.File
import java.util.UUID

/**
 * Process-wide owner of generated narration (one instance in `AppContainer`): the pack store, the
 * persisted queue, the generation worker and the keep-ahead rule. Screens, the playback engine and
 * the foreground service all go through it.
 */
@Suppress("TooManyFunctions") // The facade every screen, the engine and the service share.
class NarrationCoordinator internal constructor(
    private val context: Context,
    private val repository: AppRepository,
    private val kokoro: KokoroRuntime,
    root: File = File(context.filesDir, ROOT_DIR),
    /** Starts the foreground service that keeps generation alive; false if Android refused. */
    private val startService: () -> Boolean = { false },
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val store = NarrationStore(root)
    private val queue = NarrationQueue(File(root, QUEUE_FILE))
    private val source = RepositoryNarrationSource(repository, root)
    private val generationAllowed = MutableStateFlow(false)
    private val generator =
        NarrationGenerator(
            store = store,
            queue = queue,
            source = source,
            synthesizers = AndroidNarrationSynthesizers(context, kokoro) { queue.state.value.generateOnServer },
            onPublished = ::onPublished,
            generationAllowed = generationAllowed,
        )

    /** Fingerprints of chapters with packs, by story/chapter and library content stamp. */
    private val fingerprints = HashMap<Pair<String, String>, Pair<String, String?>>()

    /** The queue and its settings. */
    val queueState: StateFlow<NarrationQueueDocument> get() = queue.state

    /** The chapter being generated, or null while idle. */
    val progress: StateFlow<NarrationProgress?> get() = generator.progress

    /** Whether the worker may run (its foreground service is up); queued work with this false is waiting on Android. */
    val generationRunning: StateFlow<Boolean> get() = generationAllowed

    /** Bumps when a pack is published or deleted. */
    val packsRevision: StateFlow<Long> get() = store.revision

    private val serverMonitor = NarrationServerMonitor()

    /** Whether each computer is online, by server id, as last checked ([refreshServerStatus]). */
    internal val serverStatus: StateFlow<Map<String, NarrationServerStatus>> get() = serverMonitor.state

    /** Checks the computers whose status is older than [maxAgeMs]; Audio generation calls it while open. */
    suspend fun refreshServerStatus(maxAgeMs: Long) = serverMonitor.refresh(queue.state.value.servers, maxAgeMs)

    /** Loads packs and the queue (no library access), then, once the library is ready, cleans up and starts. */
    fun init(awaitRepositoryReady: suspend () -> Unit) {
        ioScope.launch {
            runCatching {
                store.load()
                queue.load()
            }.onFailure { Timber.e(it, "Narration state failed to load") }
            queueLoaded.complete(Unit)
            awaitRepositoryReady()
            runCatching {
                val chapters =
                    queue.state.value.jobs.mapNotNull { job ->
                        job.version?.let { File(store.chapterDir(job.storyId, job.chapterId), it).path }
                    }
                store.sweep(chapters.toSet())
            }.onFailure { Timber.w(it, "Narration sweep failed") }
            generator.start(workerScope)
            if (hasRunnableWork()) startService()
        }
    }

    suspend fun awaitLoaded() = store.awaitLoaded()

    private val queueLoaded = kotlinx.coroutines.CompletableDeferred<Unit>()

    suspend fun awaitQueueLoaded() = queueLoaded.await()

    /** Work may run only while the service has successfully entered the foreground. */
    internal fun setGenerationAllowed(allowed: Boolean) {
        generationAllowed.value = allowed
    }

    fun hasRunnableWork(): Boolean {
        val document = queue.state.value
        return !document.paused && NarrationPlanning.next(document.jobs) != null
    }

    /** The voice new audio uses: today's settings, resolved to a concrete engine and voice. */
    fun voiceFromSettings(settings: TtsSettings): NarrationVoice {
        val requested = settings.enginePackage
        val engine =
            when {
                requested != null -> requested
                else -> TtsEngineCatalog.phoneDefault(context) ?: GOOGLE_TTS
            }
        if (KokoroVoices.isEngine(engine)) {
            val voice = KokoroVoices.forIdentifier(settings.voiceIdentifier) ?: KokoroVoices.default
            return NarrationVoice(engine, KokoroVoices.identifier(voice), 1f, "${voice.name.substringBefore(" (")} · Kokoro")
        }
        val voiceId = settings.voiceIdentifier?.takeUnless(KokoroVoices::isKokoroIdentifier)
        val engineLabel = TtsEngineCatalog.installed(context).firstOrNull { it.packageName == engine }?.label ?: engine
        return NarrationVoice(engine, voiceId, settings.pitch, listOfNotNull(voiceId, engineLabel).joinToString(" · "))
    }

    fun currentVoice(): NarrationVoice = voiceFromSettings(repository.ttsSettings.get())

    /** Queues chapters in [voice]; [front] puts them before everything else. */
    suspend fun enqueue(
        storyId: String,
        chapterIds: List<String>,
        voice: NarrationVoice = currentVoice(),
        origin: NarrationOrigin = NarrationOrigin.MANUAL,
        front: Boolean = false,
    ) {
        if (chapterIds.isEmpty()) return
        awaitQueueLoaded()
        store.awaitLoaded()
        val ready = chapterIds.filter { hasCurrentPack(storyId, it) }.toSet()
        val requests = chapterIds.map { NarrationRequest(storyId, it, voice, origin) }
        queue.update { document ->
            document.copy(
                jobs =
                    NarrationPlanning.enqueue(
                        jobs = document.jobs,
                        requests = requests,
                        hasPack = { _, chapter -> chapter in ready },
                        front = front,
                        now = System.currentTimeMillis(),
                        newId = { UUID.randomUUID().toString() },
                    ),
            )
        }
        ActivityLog.record(
            ActivityCategory.TTS,
            "narration_enqueue",
            "storyId" to storyId,
            "chapters" to chapterIds.size,
            "origin" to origin.name,
        )
        if (hasRunnableWork()) startService()
    }

    /**
     * A chapter started playing: keep the chapters after it generated, in its voice. Runs only for
     * audio that is actually playing, never for a restored paused session.
     */
    fun onChapterPlaying(pack: NarrationPack) {
        ioScope.launch { keepAhead(pack.storyId, pack.chapterId, pack.recipe.voice) }
    }

    /** Playback wanted a chapter whose audio is missing: queue it first, in the story's voice. */
    fun onAudioMissing(
        storyId: String,
        chapterId: String,
    ) {
        ioScope.launch {
            val voice = storyVoice(storyId) ?: currentVoice()
            enqueue(storyId, listOf(chapterId), voice, NarrationOrigin.KEEP_AHEAD, front = true)
        }
    }

    /** The voice of the story's most recently generated chapter. */
    fun storyVoice(storyId: String): NarrationVoice? =
        store
            .packsFor(storyId)
            .maxByOrNull { it.index.generatedAt }
            ?.recipe
            ?.voice

    private suspend fun keepAhead(
        storyId: String,
        chapterId: String,
        voice: NarrationVoice,
    ) {
        val count = queue.state.value.keepAhead
        val story = repository.story(storyId) ?: return
        val jobs = queue.state.value.jobs
        val chapters =
            NarrationPlanning
                .keepAheadChapters(
                    chapters = story.chapters,
                    currentChapterId = chapterId,
                    count = count,
                    isPlayable = TtsContinueListening::isPlayable,
                    hasPack = { false },
                    hasJob = { id -> jobs.any { it.storyId == storyId && it.chapterId == id } },
                ).filterNot { hasCurrentPack(storyId, it) }
        if (chapters.isNotEmpty()) enqueue(storyId, chapters, voice, NarrationOrigin.KEEP_AHEAD)
    }

    private suspend fun onPublished(pack: NarrationPack) {
        synchronized(fingerprints) { fingerprints.remove(pack.storyId to pack.chapterId) }
        // Keep-ahead refills only while that story is the one being listened to.
        val listening = listeningTo
        if (listening != null && listening.first == pack.storyId) keepAhead(pack.storyId, listening.second, listening.third)
    }

    @Volatile
    private var listeningTo: Triple<String, String, NarrationVoice>? = null

    /** Playback tells which chapter is playing (null when stopped), so finished jobs can refill keep-ahead. */
    fun setListening(pack: NarrationPack?) {
        listeningTo = pack?.let { Triple(it.storyId, it.chapterId, it.recipe.voice) }
    }

    /**
     * The pack playback may use for a chapter: published and generated from the same text. [fingerprint]
     * is that of the text playback prepared.
     */
    fun readyPack(
        storyId: String,
        chapterId: String,
        fingerprint: String,
    ): NarrationPack? =
        store.pack(storyId, chapterId)?.takeIf {
            it.recipe.textFingerprint == fingerprint && it.recipe.chunkingVersion == TtsTextPreparation.CHUNKING_VERSION
        }

    private suspend fun hasCurrentPack(
        storyId: String,
        chapterId: String,
    ): Boolean {
        if (store.pack(storyId, chapterId) == null) return false
        val text = source.chapter(storyId, chapterId) ?: return false
        val pack = readyPack(storyId, chapterId, text.fingerprint) ?: return false
        return pack.recipe.title == text.spokenTitle
    }

    /** Whether a chapter can play now (reads the chapter's text; call off the main thread). */
    suspend fun isReady(
        storyId: String,
        chapterId: String,
    ): Boolean = status(storyId, chapterId) is NarrationChapterStatus.Ready

    /** One chapter's status, checking its text against its pack (reads the chapter). */
    suspend fun status(
        storyId: String,
        chapterId: String,
    ): NarrationChapterStatus {
        store.awaitLoaded()
        val story = repository.story(storyId) ?: return NarrationChapterStatus.None
        return statuses(story, setOf(chapterId))[chapterId] ?: NarrationChapterStatus.None
    }

    /**
     * Statuses of a story's chapters ([only] limits which), for the screens. A chapter with a pack
     * reads its text once to check it still matches; later calls reuse that while the chapter's
     * download time and the cleanup rules are unchanged.
     */
    suspend fun statuses(
        story: Story,
        only: Set<String>? = null,
    ): Map<String, NarrationChapterStatus> =
        withContext(Dispatchers.IO) {
            store.awaitLoaded()
            val document = queue.state.value
            val running = generator.progress.value
            val queued = document.jobs.filter { it.storyId == story.id }
            val result = HashMap<String, NarrationChapterStatus>()
            story.chapters.filter { only == null || it.id in only }.forEach { chapter ->
                val job = queued.firstOrNull { it.chapterId == chapter.id }
                val pack = store.pack(story.id, chapter.id)
                result[chapter.id] =
                    when {
                        job != null && running?.job?.id == job.id -> NarrationChapterStatus.Generating(running.fraction)
                        job?.state == NarrationJobState.FAILED -> NarrationChapterStatus.Failed(job.error ?: "Failed")
                        job != null -> NarrationChapterStatus.Queued(document.jobs.indexOf(job) + 1)
                        pack == null -> NarrationChapterStatus.None
                        fingerprintOf(story, chapter.id) == pack.recipe.textFingerprint -> NarrationChapterStatus.Ready(pack)
                        else -> NarrationChapterStatus.Outdated(pack)
                    }
            }
            result
        }

    private suspend fun fingerprintOf(
        story: Story,
        chapterId: String,
    ): String? {
        val chapter = story.chapters.firstOrNull { it.id == chapterId } ?: return null
        val key = story.id to chapterId
        val stamp =
            "${repository.libraryGeneration()}|${repository.downloadState.value.libraryVersion}|" +
                "${chapter.downloadedAt}|${repository.regexRules.get().hashCode()}|${TtsTextPreparation.CHUNKING_VERSION}"
        synchronized(fingerprints) { fingerprints[key]?.takeIf { it.first == stamp }?.let { return it.second } }
        val fingerprint = source.chapter(story.id, chapterId)?.fingerprint
        synchronized(fingerprints) { fingerprints[key] = stamp to fingerprint }
        return fingerprint
    }

    /** Estimated audio length of chapters from their text length (reads them; off the main thread). */
    suspend fun estimate(
        storyId: String,
        chapterIds: List<String>,
        voice: NarrationVoice,
    ): Pair<Long, Long> =
        withContext(Dispatchers.IO) {
            val characters =
                chapterIds.sumOf { id ->
                    source.chapter(storyId, id)?.let { text -> text.sentences.sumOf { it.length.toLong() } }
                        ?: 0L
                }
            val document = queue.state.value
            val audioMs =
                NarrationPlanning.estimatedAudioMs(
                    characters,
                    document.msPerChar[voice.engine] ?: NarrationPlanning.DEFAULT_MS_PER_CHAR,
                )
            audioMs to NarrationPlanning.generationMs(audioMs, document.speed[voice.engine] ?: NarrationPlanning.DEFAULT_SPEED)
        }

    fun pause() {
        queue.update { it.copy(paused = true, pauseReason = null, pausedForServer = false) }
    }

    fun resume() {
        queue.update { it.copy(paused = false, pauseReason = null, pausedForServer = false) }
        if (hasRunnableWork()) startService()
    }

    /** Stops generating and frees the model before the speed test; returns whether to resume after. */
    suspend fun pauseForSpeedTest(): Boolean {
        val wasRunning = !queue.state.value.paused
        if (wasRunning) queue.update { it.copy(paused = true, pauseReason = "Speed test running") }
        withTimeoutOrNull(SPEED_TEST_WAIT_MS) { generator.progress.first { it == null } }
        return wasRunning
    }

    fun setKeepAhead(count: Int) {
        queue.update { it.copy(keepAhead = count) }
    }

    fun markScreenOffPromptShown() {
        queue.update { it.copy(screenOffPromptShown = true) }
    }

    /** Adds a computer, or saves changes to one with the same id; returns the saved server. */
    fun saveServer(
        id: String?,
        name: String,
        address: String,
        tailscaleAddress: String,
        token: String,
    ): NarrationServer {
        val server =
            NarrationServer(
                id = id ?: UUID.randomUUID().toString(),
                name = name.trim(),
                address = address.trim(),
                token = NarrationServerPlanning.normalizeToken(token),
                tailscaleAddress = tailscaleAddress.trim(),
            )
        queue.update { document ->
            val exists = document.servers.any { it.id == server.id }
            document.copy(
                servers =
                    if (exists) {
                        document.servers.map {
                            if (it.id ==
                                server.id
                            ) {
                                server
                            } else {
                                it
                            }
                        }
                    } else {
                        document.servers + server
                    },
            )
        }
        return server
    }

    /** Removes a computer; chapters it was generating continue on this phone. */
    fun removeServer(id: String) {
        queue.update { document ->
            document.copy(
                servers = document.servers.filter { it.id != id },
                generateOn = document.generateOn.takeUnless { it == id },
            )
        }
    }

    /**
     * Where Kokoro chapters generate: a server id, or null for this phone. A chapter in progress
     * switches at its next sentence. Choosing a place resumes a queue that paused because the previous
     * one could not be reached.
     */
    fun setGenerateOn(serverId: String?) {
        queue.update { document ->
            val resume = document.paused && document.pausedForServer
            document.copy(
                generateOn = serverId?.takeIf { id -> document.servers.any { it.id == id } },
                paused = document.paused && !resume,
                pauseReason = if (resume) null else document.pauseReason,
                pausedForServer = false,
            )
        }
        if (hasRunnableWork()) startService()
    }

    /**
     * Connects to [server] (local network first, then Tailscale) and speaks one sentence per model
     * copy: a summary of the connection, or why it failed. Also records whether it is online.
     */
    suspend fun testServer(server: NarrationServer): Result<String> {
        var route: NarrationServerRoute? = null
        val result =
            runCatching {
                val reached =
                    reachServer(server) { client ->
                        client.info().also { info ->
                            NarrationServerPlanning.incompatibility(info, server.name)?.let { throw NarrationServerProblem(it) }
                        }
                    }
                route = reached.route
                val client = reached.client
                val info = reached.value
                try {
                    // One sentence per model copy at once, as generation runs, so the speed is the server's total.
                    val started = System.currentTimeMillis()
                    val clips =
                        coroutineScope {
                            (0 until info.workers.coerceIn(1, NarrationServerPlanning.MAX_IN_FLIGHT))
                                .map { index ->
                                    async { client.synthesize(TEST_SENTENCES[index % TEST_SENTENCES.size], KokoroVoices.default.speakerId) }
                                }.awaitAll()
                        }
                    if (clips.any { it == null }) throw NarrationServerProblem("${server.name} connected but could not generate audio")
                    NarrationServerPlanning.testSummary(
                        info,
                        reached.route,
                        clips.sumOf { it!!.durationMs },
                        System.currentTimeMillis() - started,
                    )
                } finally {
                    client.close()
                }
            }
        val failure = result.exceptionOrNull()
        if (failure == null || failure is NarrationServerProblem) {
            val reachedRoute = route
            serverMonitor.record(
                server,
                NarrationServerStatus(
                    server.locationKey,
                    System.currentTimeMillis(),
                    online = reachedRoute != null || failure is NarrationServerRejected,
                    route = reachedRoute?.route,
                    problem = if (failure is NarrationServerRejected) "wrong token" else null,
                ),
            )
        }
        return result
    }

    fun remove(jobIds: Set<String>) {
        queue.update { it.copy(jobs = it.jobs.filter { job -> job.id !in jobIds }) }
    }

    fun moveToTop(jobId: String) {
        queue.update { it.copy(jobs = NarrationPlanning.moveToTop(it.jobs, jobId)) }
    }

    /** Stops everything: removes every job, including the running one (its partial audio is dropped). */
    fun clearQueue() {
        queue.update { it.copy(jobs = emptyList()) }
        ActivityLog.record(ActivityCategory.TTS, "narration_queue_cleared")
    }

    /**
     * Puts every job of [storyId] before the rest, so that story generates next. A chapter of another
     * story stops at its next sentence and resumes from there later.
     */
    fun moveStoryToTop(storyId: String) {
        queue.update { it.copy(jobs = NarrationPlanning.storyFirst(it.jobs, storyId)) }
    }

    fun retry(jobId: String) {
        queue.update { it.copy(jobs = NarrationPlanning.retry(it.jobs, jobId)) }
        if (hasRunnableWork()) startService()
    }

    fun retryAll() {
        queue.update { it.copy(jobs = NarrationPlanning.retryAll(it.jobs)) }
        if (hasRunnableWork()) startService()
    }

    fun clearFailed() {
        queue.update { it.copy(jobs = it.jobs.filter { job -> job.state != NarrationJobState.FAILED }) }
    }

    /** Deletes the audio of these chapters (and their jobs). */
    suspend fun deleteAudio(
        storyId: String,
        chapterIds: Collection<String>,
    ) = withContext(Dispatchers.IO) {
        val ids = chapterIds.toSet()
        queue.update { it.copy(jobs = it.jobs.filterNot { job -> job.storyId == storyId && job.chapterId in ids }) }
        ids.forEach { store.deleteChapter(storyId, it) }
        ActivityLog.record(ActivityCategory.TTS, "narration_deleted", "storyId" to storyId, "chapters" to ids.size)
    }

    /** The story left the library (or its audio is deleted): remove everything it had. */
    suspend fun deleteStory(storyId: String) =
        withContext(Dispatchers.IO) {
            queue.update { it.copy(jobs = it.jobs.filterNot { job -> job.storyId == storyId }) }
            store.deleteStory(storyId)
            ActivityLog.record(ActivityCategory.TTS, "narration_story_deleted", "storyId" to storyId)
        }

    /** Packs whose story is no longer in the library, by story id. */
    fun detachedPacks(): Map<String, List<NarrationPack>> =
        store
            .allPacks()
            .filter { repository.story(it.storyId)?.chapters?.any { chapter -> chapter.id == it.chapterId } != true }
            .groupBy { it.storyId }

    fun freeBytes(): Long = source.freeBytes()

    /** A chapter's title in the narration screens, or its id while it is unknown. */
    fun chapterTitle(
        storyId: String,
        chapterId: String,
    ): String =
        repository
            .story(storyId)
            ?.chapters
            ?.firstOrNull { it.id == chapterId }
            ?.title ?: chapterId

    companion object {
        const val ROOT_DIR = "narration"
        private const val QUEUE_FILE = "queue.json"
        private const val GOOGLE_TTS = "com.google.android.tts"
        private const val SPEED_TEST_WAIT_MS = 60_000L
        private val TEST_SENTENCES =
            listOf(
                "Beyond the walls, the river had swollen with three days of rain, and the old bridge groaned under every cart.",
                "He set the lantern on the table and waited for the others to find their seats.",
                "By the time the fourth watch began, Captain Arlen had read the letter twice and folded it into his coat.",
                "\"You're late,\" Mira said, without looking up from the map she had spread across the desk.",
            )
    }
}

/** Reads chapters for generation from the repository, through the same text pipeline playback uses. */
private class RepositoryNarrationSource(
    private val repository: AppRepository,
    private val root: File,
) : NarrationChapterSource {
    override suspend fun chapter(
        storyId: String,
        chapterId: String,
    ): NarrationChapterText? {
        val story = repository.story(storyId) ?: return null
        val chapter = story.chapters.firstOrNull { it.id == chapterId } ?: return null
        val html = repository.readChapter(chapter) ?: chapter.content ?: return null
        val narration = TtsTextPreparation.prepareTtsNarration(html, repository.regexRules.get())
        return NarrationChapterText(
            storyTitle = story.title,
            chapterTitle = chapter.title,
            sentences = narration.map { it.text },
            spokenTitle = TtsNarrationPlanning.spokenChapterTitle(chapter.title, narration.firstOrNull()?.text),
        )
    }

    override fun libraryGeneration(): Long = repository.libraryGeneration()

    /** Logged with each finished chapter, to tell background slowdowns from slow chapters. */
    override fun environment(): List<Pair<String, Any?>> {
        val info = ActivityManager.RunningAppProcessInfo().also(ActivityManager::getMyMemoryState)
        return listOf("cpusAllowed" to KokoroCpu.allowedCoreCount(), "importance" to info.importance)
    }

    override fun freeBytes(): Long =
        runCatching {
            root.mkdirs()
            StatFs(root.path).availableBytes
        }.getOrDefault(Long.MAX_VALUE)
}
