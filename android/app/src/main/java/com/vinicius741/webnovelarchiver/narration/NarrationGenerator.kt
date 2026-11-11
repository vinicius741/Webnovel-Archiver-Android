package com.vinicius741.webnovelarchiver.narration

import com.vinicius741.webnovelarchiver.cleanup.TtsTextPreparation
import com.vinicius741.webnovelarchiver.data.diagnostics.ActivityCategory
import com.vinicius741.webnovelarchiver.data.diagnostics.ActivityLog
import com.vinicius741.webnovelarchiver.tts.BYTES_PER_FRAME
import com.vinicius741.webnovelarchiver.tts.TtsPcmClip
import com.vinicius741.webnovelarchiver.tts.TtsPcmPlanning
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.io.IOException

/** A chapter's spoken text as generation needs it. */
internal data class NarrationChapterText(
    val storyTitle: String,
    val chapterTitle: String,
    val sentences: List<String>,
    /** The chapter title to speak first, or null when the text already opens with it. */
    val spokenTitle: String?,
) {
    val fingerprint: String get() = NarrationPlanning.fingerprint(sentences, TtsTextPreparation.CHUNKING_VERSION)
}

/** Where generation reads chapters from; the repository in production, a fake in tests. */
internal interface NarrationChapterSource {
    /** Null when the story or chapter is gone, or the chapter has no downloaded text. */
    suspend fun chapter(
        storyId: String,
        chapterId: String,
    ): NarrationChapterText?

    /** Changes when the whole library is replaced (restore, clear). */
    fun libraryGeneration(): Long

    /** Bytes free where packs are written. */
    fun freeBytes(): Long
}

/**
 * Generates queued chapters one at a time, sentence by sentence, into pack versions. Work is
 * resumable: a version's recipe fixes what each clip holds, so after a process death the job picks
 * up at the first missing clip. Before publishing it checks the job is still wanted and the text and
 * library unchanged, so audio never lands under the wrong text.
 */
@Suppress("TooManyFunctions") // One worker: its steps are small on purpose.
internal class NarrationGenerator(
    private val store: NarrationStore,
    private val queue: NarrationQueue,
    private val source: NarrationChapterSource,
    private val synthesizers: NarrationSynthesizerFactory,
    private val now: () -> Long = System::currentTimeMillis,
    private val onPublished: (NarrationPack) -> Unit = {},
) {
    private val _progress = MutableStateFlow<NarrationProgress?>(null)

    /** The chapter being generated, or null while idle. */
    val progress: StateFlow<NarrationProgress?> get() = _progress
    private var synthesizer: NarrationSynthesizer? = null
    private var worker: Job? = null

    fun start(scope: CoroutineScope) {
        if (worker?.isActive == true) return
        worker = scope.launch { runLoop() }
    }

    fun stop() {
        worker?.cancel()
        worker = null
        closeSynthesizer()
        _progress.value = null
    }

    /** Runs jobs until cancelled, sleeping while the queue is empty or paused. */
    suspend fun runLoop() {
        try {
            while (currentCoroutineContext().isActive) {
                val document = queue.state.value
                val job = if (document.paused) null else NarrationPlanning.next(document.jobs)
                if (job == null) {
                    // Nothing to do: free the model, then wait for the queue to change.
                    closeSynthesizer()
                    _progress.value = null
                    queue.state.first { it !== document }
                    continue
                }
                runJob(job)
            }
        } finally {
            closeSynthesizer()
        }
    }

    // A failure in one chapter must not stop the queue; cancellation still escapes.
    @Suppress("TooGenericExceptionCaught")
    internal suspend fun runJob(job: NarrationJob) {
        try {
            when (val outcome = generate(job)) {
                JobOutcome.Done, JobOutcome.Interrupted -> Unit
                is JobOutcome.Failed -> fail(job, outcome.reason)
                JobOutcome.Dropped -> queue.update { it.copy(jobs = it.jobs.filter { other -> other.id != job.id }) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (unavailable: NarrationVoiceUnavailable) {
            fail(job, unavailable.message ?: "The voice is unavailable")
        } catch (io: IOException) {
            if (source.freeBytes() < NarrationPlanning.FREE_SPACE_MARGIN_BYTES) {
                pause("Not enough storage")
            } else {
                Timber.w(io, "Narration generation failed writing")
                fail(job, "Could not save the audio: ${io.message}")
            }
        } catch (error: Exception) {
            Timber.e(error, "Narration generation failed")
            fail(job, error.message ?: error.javaClass.simpleName)
        }
    }

    private sealed interface JobOutcome {
        data object Done : JobOutcome

        /** Paused, cancelled or changed mid-chapter; the queue decides what runs next. */
        data object Interrupted : JobOutcome

        /** The chapter no longer exists: forget the job quietly. */
        data object Dropped : JobOutcome

        data class Failed(
            val reason: String,
        ) : JobOutcome
    }

    private suspend fun generate(job: NarrationJob): JobOutcome {
        val startedGeneration = source.libraryGeneration()
        val text = source.chapter(job.storyId, job.chapterId) ?: return JobOutcome.Dropped
        if (text.sentences.isEmpty()) return JobOutcome.Failed("The chapter has no text to read")
        val voice = openSynthesizer(job.voice)
        val recipe =
            NarrationRecipe(
                storyId = job.storyId,
                chapterId = job.chapterId,
                chunkingVersion = TtsTextPreparation.CHUNKING_VERSION,
                textFingerprint = text.fingerprint,
                title = text.spokenTitle,
                sentences = text.sentences,
                voice = job.voice,
                voiceKey = voice.voiceKey,
            )
        val current = store.pack(job.storyId, job.chapterId)
        if (current != null && current.recipe.copy(voice = recipe.voice) == recipe) {
            // Already generated exactly like this: nothing to do.
            removeJob(job)
            return JobOutcome.Done
        }
        val estimate = NarrationPlanning.estimatedBytes(estimatedAudioMs(job.voice.engine, recipe))
        if (source.freeBytes() < estimate + NarrationPlanning.FREE_SPACE_MARGIN_BYTES) {
            pause("Not enough storage")
            return JobOutcome.Interrupted
        }
        val versionDir = versionFor(job, recipe)
        ActivityLog.record(ActivityCategory.TTS, "narration_generate", "storyId" to job.storyId, "chapterId" to job.chapterId)
        val stats = GenerationStats()
        writeClips(job, text, recipe, versionDir, voice, current, stats)?.let { return it }
        checkBeforePublish(job, recipe, versionDir, startedGeneration)?.let { return it }
        val index = buildIndex(versionDir, recipe) ?: return JobOutcome.Failed("The voice changed sample rate mid-chapter")
        val pack = store.publish(recipe, versionDir, index)
        recordStats(job.voice.engine, stats)
        removeJob(job)
        ActivityLog.record(ActivityCategory.TTS, "narration_published", "storyId" to job.storyId, "chapterId" to job.chapterId)
        onPublished(pack)
        return JobOutcome.Done
    }

    /** Writes every missing clip; null when all are there, else why it stopped. */
    @Suppress("LongParameterList") // One chapter's generation state, passed through rather than stored.
    private suspend fun writeClips(
        job: NarrationJob,
        text: NarrationChapterText,
        recipe: NarrationRecipe,
        versionDir: File,
        voice: NarrationSynthesizer,
        current: NarrationPack?,
        stats: GenerationStats,
    ): JobOutcome? {
        val reuse = if (current != null && current.recipe.voiceKey == recipe.voiceKey) reusableClips(current) else emptyMap()
        val items =
            listOfNotNull(recipe.title?.let { store.titleFile(versionDir) to it }) +
                recipe.sentences.mapIndexed { index, sentence -> store.clipFile(versionDir, index) to sentence }
        var consecutiveEmpty = 0
        for ((done, item) in items.withIndex()) {
            publishProgress(job, text, done, items.size, recipe, stats)
            if (!stillWanted(job)) return interrupted(job, versionDir)
            val (target, sentence) = item
            if (target.isFile) continue
            val reused = reuse[sentence]?.let(store::readClip)
            val clip =
                reused ?: synthesize(voice, sentence, versionDir, stats)
                    ?: return JobOutcome.Failed("Could not generate: \"${sentence.take(FAILED_TEXT_CHARS)}\"")
            consecutiveEmpty = if (clip.pcm.isEmpty() && reused == null) consecutiveEmpty + 1 else 0
            if (consecutiveEmpty >= MAX_CONSECUTIVE_EMPTY) return JobOutcome.Failed("The voice produced no audio")
            store.writeClip(target, clip)
        }
        return null
    }

    /**
     * Publishing is allowed only while the job is still wanted and the chapter's text and the library
     * are what the audio was generated from; null when it may go ahead.
     */
    private suspend fun checkBeforePublish(
        job: NarrationJob,
        recipe: NarrationRecipe,
        versionDir: File,
        startedGeneration: Long,
    ): JobOutcome? {
        if (!stillWanted(job)) return interrupted(job, versionDir)
        val latest = source.chapter(job.storyId, job.chapterId)
        if (latest == null || source.libraryGeneration() != startedGeneration) {
            store.discardVersion(versionDir)
            return if (latest == null) JobOutcome.Dropped else JobOutcome.Interrupted
        }
        if (latest.fingerprint != recipe.textFingerprint || latest.spokenTitle != recipe.title) {
            // The text changed while generating: start over from the new text (unchanged sentences are reused).
            store.discardVersion(versionDir)
            queue.update { document -> document.copy(jobs = document.jobs.map { if (it.id == job.id) it.copy(version = null) else it }) }
            return JobOutcome.Interrupted
        }
        return null
    }

    /** The job's own unfinished version when it was built from the same recipe; else a new one. */
    private fun versionFor(
        job: NarrationJob,
        recipe: NarrationRecipe,
    ): File {
        job.version?.let { version ->
            val existing = store.openVersion(job.storyId, job.chapterId, version)
            if (existing != null && existing.second == recipe) return existing.first
            existing?.first?.let(store::discardVersion)
        }
        val created = store.createVersion(recipe)
        queue.update { document ->
            document.copy(
                jobs =
                    document.jobs.map {
                        if (it.id ==
                            job.id
                        ) {
                            it.copy(version = created.name)
                        } else {
                            it
                        }
                    },
            )
        }
        return created
    }

    private fun reusableClips(pack: NarrationPack): Map<String, File> {
        val clips = HashMap<String, File>()
        pack.recipe.sentences.forEachIndexed { index, sentence -> clips.putIfAbsent(sentence, store.clipFile(pack.dir, index)) }
        pack.recipe.title?.let { clips.putIfAbsent(it, store.titleFile(pack.dir)) }
        return clips
    }

    private suspend fun synthesize(
        voice: NarrationSynthesizer,
        sentence: String,
        versionDir: File,
        stats: GenerationStats,
    ): TtsPcmClip? {
        val temporary = File(versionDir, "render.wav.tmp")
        val startedAt = now()
        try {
            // One retry: a single hiccup must not fail a whole chapter.
            val succeeded = voice.synthesize(sentence, temporary) || voice.synthesize(sentence, temporary)
            if (!succeeded) return null
            val parsed = temporary.takeIf(File::isFile)?.readBytes()?.let(TtsPcmPlanning::parseWav) ?: return null
            val trimmed = TtsPcmPlanning.trimSilence(parsed)
            stats.add(sentence.length, trimmed.durationMs, now() - startedAt)
            return trimmed
        } finally {
            temporary.delete()
        }
    }

    private fun buildIndex(
        versionDir: File,
        recipe: NarrationRecipe,
    ): NarrationPackIndex? {
        val clips =
            recipe.sentences.indices.map { store.clipFile(versionDir, it) } +
                listOfNotNull(recipe.title?.let { store.titleFile(versionDir) })
        val rates = clips.mapNotNull { file -> store.readClip(file)?.takeIf { it.pcm.isNotEmpty() }?.sampleRate }.toSet()
        if (rates.size > 1) return null
        val sampleRate = rates.firstOrNull() ?: DEFAULT_SAMPLE_RATE

        fun durationOf(file: File): Long =
            TtsPcmPlanning.framesToMs(((file.length() - CLIP_HEADER_BYTES).coerceAtLeast(0L)) / BYTES_PER_FRAME, sampleRate)
        val durations = recipe.sentences.indices.map { durationOf(store.clipFile(versionDir, it)) }
        val title = recipe.title?.let { durationOf(store.titleFile(versionDir)) }
        return NarrationPackIndex(
            sampleRate = sampleRate,
            sentenceDurationsMs = durations,
            titleDurationMs = title,
            totalDurationMs = durations.sum() + (title ?: 0L),
            bytes = clips.sumOf { it.length() },
            generatedAt = now(),
        )
    }

    /** A removed job's unfinished version is dropped now; a paused one keeps it to resume. */
    private fun interrupted(
        job: NarrationJob,
        versionDir: File,
    ): JobOutcome {
        if (queue.state.value.jobs
                .none { it.id == job.id }
        ) {
            store.discardVersion(versionDir)
        }
        return JobOutcome.Interrupted
    }

    private fun stillWanted(job: NarrationJob): Boolean {
        val document = queue.state.value
        if (document.paused) return false
        val queued = document.jobs.firstOrNull { it.id == job.id } ?: return false
        return queued.voice == job.voice && queued.state == NarrationJobState.QUEUED
    }

    private suspend fun openSynthesizer(voice: NarrationVoice): NarrationSynthesizer {
        synthesizer?.takeIf { it.voice == voice }?.let { return it }
        closeSynthesizer()
        return synthesizers.open(voice).also { synthesizer = it }
    }

    private fun closeSynthesizer() {
        synthesizer?.close()
        synthesizer = null
    }

    private fun publishProgress(
        job: NarrationJob,
        text: NarrationChapterText,
        done: Int,
        total: Int,
        recipe: NarrationRecipe,
        stats: GenerationStats,
    ) {
        val document = queue.state.value
        val speed = stats.speed() ?: document.speed[job.voice.engine] ?: NarrationPlanning.DEFAULT_SPEED
        // This chapter's own voice rate, once enough of it is measured, beats the stored average.
        val measured = stats.msPerChar().takeIf { stats.characters >= MIN_STATS_CHARACTERS }
        val chapterAudio =
            measured?.let { NarrationPlanning.estimatedAudioMs(recipe.characters(), it) } ?: estimatedAudioMs(job.voice.engine, recipe)
        val remainingHere = if (total == 0) 0L else chapterAudio * (total - done) / total
        val others = (document.jobs.count { it.state == NarrationJobState.QUEUED } - 1).coerceAtLeast(0)
        _progress.value =
            NarrationProgress(
                job = job,
                storyTitle = text.storyTitle,
                chapterTitle = text.chapterTitle,
                done = done,
                total = total,
                etaMs = NarrationPlanning.generationMs(remainingHere + others * chapterAudio, speed),
            )
    }

    private fun estimatedAudioMs(
        engine: String,
        recipe: NarrationRecipe,
    ): Long {
        val msPerChar = queue.state.value.msPerChar[engine] ?: NarrationPlanning.DEFAULT_MS_PER_CHAR
        return NarrationPlanning.estimatedAudioMs(recipe.characters(), msPerChar)
    }

    private fun recordStats(
        engine: String,
        stats: GenerationStats,
    ) {
        if (stats.characters < MIN_STATS_CHARACTERS) return
        queue.update { document ->
            document.copy(
                msPerChar = document.msPerChar + (engine to NarrationPlanning.blend(document.msPerChar[engine], stats.msPerChar())),
                speed =
                    stats.speed()?.let { document.speed + (engine to NarrationPlanning.blend(document.speed[engine], it)) }
                        ?: document.speed,
            )
        }
    }

    private fun removeJob(job: NarrationJob) {
        queue.update { document -> document.copy(jobs = document.jobs.filter { it.id != job.id }) }
    }

    private fun fail(
        job: NarrationJob,
        reason: String,
    ) {
        Timber.w("Narration job failed: %s", reason)
        ActivityLog.record(
            ActivityCategory.TTS,
            "narration_failed",
            "storyId" to job.storyId,
            "chapterId" to job.chapterId,
            "reason" to reason,
        )
        queue.update { document ->
            document.copy(
                jobs =
                    document.jobs.map {
                        if (it.id ==
                            job.id
                        ) {
                            it.copy(state = NarrationJobState.FAILED, error = reason)
                        } else {
                            it
                        }
                    },
            )
        }
    }

    private fun pause(reason: String) {
        ActivityLog.record(ActivityCategory.TTS, "narration_paused", "reason" to reason)
        queue.update { it.copy(paused = true, pauseReason = reason) }
    }

    private class GenerationStats {
        var characters = 0L
        var audioMs = 0L
        var realMs = 0L

        fun add(
            chars: Int,
            audio: Long,
            real: Long,
        ) {
            characters += chars
            audioMs += audio
            realMs += real
        }

        fun msPerChar(): Double = if (characters == 0L) 0.0 else audioMs.toDouble() / characters

        fun speed(): Double? = if (realMs < MIN_STATS_REAL_MS) null else audioMs.toDouble() / realMs
    }

    private companion object {
        const val MAX_CONSECUTIVE_EMPTY = 5
        const val FAILED_TEXT_CHARS = 60
        const val DEFAULT_SAMPLE_RATE = 24_000
        const val CLIP_HEADER_BYTES = 4L
        const val MIN_STATS_CHARACTERS = 500L
        const val MIN_STATS_REAL_MS = 2_000L
    }
}

private fun NarrationRecipe.characters(): Long = sentences.sumOf { it.length.toLong() } + (title?.length ?: 0)
