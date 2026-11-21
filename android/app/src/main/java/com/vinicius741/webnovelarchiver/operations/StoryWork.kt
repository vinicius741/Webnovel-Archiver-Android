package com.vinicius741.webnovelarchiver.operations

import com.vinicius741.webnovelarchiver.cleanup.CleanupEngine
import com.vinicius741.webnovelarchiver.data.repository.AppRepository
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.EpubConfig
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.epub.EpubEngine
import com.vinicius741.webnovelarchiver.source.SourceRegistry
import com.vinicius741.webnovelarchiver.sync.StorySyncEngine
import com.vinicius741.webnovelarchiver.sync.StorySyncMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/** Tallies from one "Apply text cleanup" run over a story's downloaded chapters. */
data class CleanupRunStats(
    val totalChapters: Int,
    val processedChapters: Int,
    val failedChapters: Int,
    val changedChapters: Int,
    val sentencesRemoved: Int,
    val regexMatchesRemoved: Int,
)

/** The chapter files and rules a cleanup run reads and rewrites. */
interface CleanupFiles {
    /** The story's cleanup rules, compiled once per run. Called off the main thread. */
    fun cleanerFor(story: Story): (String) -> CleanupEngine.CleanupResult

    suspend fun read(chapter: Chapter): String?

    suspend fun overwrite(
        chapter: Chapter,
        html: String,
    ): Boolean

    /** Marks the story's EPUB stale; false when the story no longer exists. */
    suspend fun markCleanupApplied(storyId: String): Boolean
}

/** Writes a story's EPUB files and returns how many it wrote. */
fun interface EpubGenerator {
    suspend fun generate(
        story: Story,
        chapters: List<Chapter>,
        config: EpubConfig,
        originalChapterNumbers: List<Int>?,
        progress: (String) -> Unit,
    ): Int
}

/**
 * The content work on one story (sync, text cleanup, EPUB). Each call holds the
 * [StoryOperations.content] slot from start to finish and publishes its progress there; screens
 * observe that slot, and the caller only confirms, toasts and navigates. Busy checks stay with the
 * caller, which owns the messages.
 */
class StoryWork(
    private val operations: StoryOperations,
    private val manualSync: ManualStorySync,
    private val cleanupFiles: CleanupFiles,
    private val epub: EpubGenerator,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** The sync a "Sync all" item runs: like [manualSync] but without the Patreon refresh. */
    private val batchSync: ManualStorySync = manualSync,
) {
    /** Syncs an existing story in place and plans the downloads of what it found. */
    suspend fun sync(
        story: Story,
        mode: StorySyncMode,
    ): ManualStorySyncResult =
        holding(story.id, StoryOperationKind.SYNC, STARTING) {
            manualSync.run(story.sourceUrl, story.tabId, mode, story.sourceId) { message ->
                operations.updateContent(story.id, StoryOperationKind.SYNC, message)
            }
        }

    /**
     * Syncs [story] from Updates, listed in [StoryOperations.updatesSyncs] with its progress until it
     * ends. A row sync ([inBatch] false) also holds the content slot when it is free, so the story
     * page shows it; a "Sync all" item never takes the slot and skips the Patreon refresh. The
     * caller checks that the story is not already syncing.
     */
    suspend fun syncFromUpdates(
        story: Story,
        inBatch: Boolean,
    ): ManualStorySyncResult {
        operations.startUpdatesSync(story.id, STARTING)
        val ownsSlot = !inBatch && operations.content.value == null
        if (ownsSlot) operations.setContent(story.id, StoryOperationKind.SYNC, STARTING)
        try {
            return (if (inBatch) batchSync else manualSync).run(
                story.sourceUrl,
                story.tabId,
                StorySyncMode.Default,
                story.sourceId,
            ) { message ->
                operations.updateUpdatesSync(story.id, message)
                if (ownsSlot) operations.updateContent(story.id, StoryOperationKind.SYNC, message)
            }
        } finally {
            operations.endUpdatesSync(story.id)
            if (ownsSlot) operations.clearContent(story.id, StoryOperationKind.SYNC)
        }
    }

    /**
     * Applies the cleanup rules to [downloaded]. A failing chapter is counted and skipped; a story
     * deleted mid-run, and cancellation, abort the run.
     */
    @Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException")
    suspend fun cleanup(
        story: Story,
        downloaded: List<Chapter>,
    ): CleanupRunStats =
        holding(story.id, StoryOperationKind.CLEANUP, PROCESSING) {
            withContext(io) {
                val clean = cleanupFiles.cleanerFor(story)
                var processed = 0
                var errors = 0
                var changed = 0
                var sentencesRemoved = 0
                var regexMatchesRemoved = 0
                val startedAt = System.nanoTime()
                downloaded.forEachIndexed { index, chapter ->
                    operations.updateContent(
                        story.id,
                        StoryOperationKind.CLEANUP,
                        "Processing ${index + 1}/${downloaded.size}: ${chapter.title}",
                        (index + 1).toFloat() / downloaded.size,
                    )
                    try {
                        val html = cleanupFiles.read(chapter) ?: error("Downloaded chapter file is missing")
                        val result = clean(html)
                        // Skip writes whose cleaned HTML equals the original.
                        if (result.html != html) {
                            // Persist the stale marker before the first chapter modification:
                            // cancellation, process death, or a later failure must never leave modified
                            // chapter files under an EPUB still marked current. A run that changes
                            // nothing leaves the EPUB's freshness untouched.
                            if (changed == 0 && !cleanupFiles.markCleanupApplied(story.id)) throw CleanupStoryRemovedException()
                            check(cleanupFiles.overwrite(chapter, result.html)) { "Downloaded chapter file is missing" }
                            changed += 1
                        }
                        processed += 1
                        sentencesRemoved += result.sentencesRemoved
                        regexMatchesRemoved += result.regexMatchesRemoved
                    } catch (error: Exception) {
                        // The per-chapter boundary must rethrow cancellation, not count it as an error, and
                        // a deleted story aborts the whole run rather than failing chapter by chapter.
                        if (error is CancellationException || error is CleanupStoryRemovedException) throw error
                        errors += 1
                    }
                }
                Timber.i(
                    "Text cleanup of %d chapters (%d changed) took %d ms",
                    downloaded.size,
                    changed,
                    (System.nanoTime() - startedAt) / NANOS_PER_MILLI,
                )
                CleanupRunStats(
                    totalChapters = downloaded.size,
                    processedChapters = processed,
                    failedChapters = errors,
                    changedChapters = changed,
                    sentencesRemoved = sentencesRemoved,
                    regexMatchesRemoved = regexMatchesRemoved,
                )
            }
        }

    /** Writes the EPUB files for [chapters]; returns how many files it wrote. */
    suspend fun epub(
        story: Story,
        chapters: List<Chapter>,
        config: EpubConfig,
        originalChapterNumbers: List<Int>?,
    ): Int =
        holding(story.id, StoryOperationKind.EPUB, PREPARING) {
            epub.generate(story, chapters, config, originalChapterNumbers) { message ->
                operations.updateContent(story.id, StoryOperationKind.EPUB, message, epubProgressFromMessage(message))
            }
        }

    private suspend fun <T> holding(
        storyId: String,
        kind: StoryOperationKind,
        message: String,
        block: suspend () -> T,
    ): T {
        operations.setContent(storyId, kind, message)
        try {
            return block()
        } finally {
            operations.clearContent(storyId, kind)
        }
    }

    companion object {
        /** The app's story work, backed by the repository and the sync and EPUB engines. */
        fun create(
            operations: StoryOperations,
            repository: AppRepository,
            syncEngine: StorySyncEngine,
            epubEngine: EpubEngine,
        ): StoryWork =
            StoryWork(
                operations = operations,
                manualSync = ManualStorySync(repository, syncEngine),
                cleanupFiles = RepositoryCleanupFiles(repository),
                epub = { story, chapters, config, originals, progress ->
                    epubEngine.generate(story, chapters, config, originals, progress).size
                },
                batchSync = ManualStorySync(repository, syncEngine, refreshPatreonStats = false),
            )
    }
}

/** EPUB progress messages read "Generating EPUB 3/10..."; anything else has no fraction. */
internal fun epubProgressFromMessage(message: String): Float? {
    val match = Regex("""Generating EPUB\s+(\d+)/(\d+)""").find(message) ?: return null
    val current = match.groupValues[1].toFloatOrNull() ?: return null
    val total = match.groupValues[2].toFloatOrNull()?.takeIf { it > 0f } ?: return null
    return (current / total).coerceIn(0f, 1f)
}

private class RepositoryCleanupFiles(
    private val repository: AppRepository,
) : CleanupFiles {
    override fun cleanerFor(story: Story): (String) -> CleanupEngine.CleanupResult {
        val sentences = repository.sentenceRemovalList.get()
        val rules = repository.regexRules.get()
        val noticeMarker = SourceRegistry.getProvider(story.sourceId, story.sourceUrl)?.insertedNoticeMarker
        return { html -> CleanupEngine.shared.applyDownloadWithStats(html, sentences, rules, noticeMarker) }
    }

    override suspend fun read(chapter: Chapter): String? = repository.readChapter(chapter)

    override suspend fun overwrite(
        chapter: Chapter,
        html: String,
    ): Boolean = repository.overwriteChapter(chapter, html)

    override suspend fun markCleanupApplied(storyId: String): Boolean = repository.markCleanupApplied(storyId) != null
}

private class CleanupStoryRemovedException : IllegalStateException("Story was removed while cleanup was running")

private const val STARTING = "Starting..."
private const val PROCESSING = "Processing..."
private const val PREPARING = "Preparing..."
private const val NANOS_PER_MILLI = 1_000_000L
