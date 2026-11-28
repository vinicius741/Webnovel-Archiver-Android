package com.vinicius741.webnovelarchiver.data.storage

import com.vinicius741.webnovelarchiver.cleanup.ChapterMarkupCompactor
import com.vinicius741.webnovelarchiver.data.diagnostics.ActivityCategory
import com.vinicius741.webnovelarchiver.data.diagnostics.ActivityLog
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.Story
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Applies a [ChapterMarkupCompactor] to stored chapter bytes. */
internal object ChapterMarkupCompaction {
    /** [bytes] compacted; the same instance when nothing changed or the file is not valid UTF-8. */
    fun compactBytes(
        bytes: ByteArray,
        compactor: ChapterMarkupCompactor,
    ): ByteArray {
        // A strict decode, so a file with broken bytes is copied as it is instead of having them replaced.
        val text =
            try {
                Charsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (_: CharacterCodingException) {
                return bytes
            }
        val compacted = compactor.compact(text)
        return if (compacted === text) bytes else compacted.toByteArray(Charsets.UTF_8)
    }

    /** Compacts [file] in place with an atomic replace; returns the bytes saved, 0 when nothing changed. */
    fun compactFile(
        file: File,
        compactor: ChapterMarkupCompactor,
    ): Long {
        val bytes = file.readBytes()
        val compacted = compactBytes(bytes, compactor)
        if (compacted === bytes) return 0
        AtomicFileWrites.writeAtomically(file) { it.write(compacted) }
        return (bytes.size - compacted.size).toLong()
    }
}

/**
 * One background pass that drops source markup noise from chapter files saved before downloads
 * dropped it. `chapter_compaction.txt` in the storage root records finished stories, so a pass cut
 * short by the process dying resumes where it stopped, and once it has finished it never runs again.
 * A restore or Clear storage replaces the root and the file with it, so the restored library gets
 * one pass too. Each file is read, compacted and atomically replaced under the storage lock, and
 * compacting compacted HTML changes nothing, so a repeated file is harmless.
 *
 * The pass pauses between files so the storage lock it re-takes for each one never starves the
 * reader, playback saves or downloads. A file that cannot be rewritten is skipped: its story stays
 * pending for the next launch, and the stories after it still run.
 */
internal class StoredChapterCompaction(
    /** The storage root; the progress file lives here. */
    private val root: File,
    /** The storage lock: held while progress is read or saved, and around each file's rewrite. */
    private val lock: Any,
    /** [AppStorage.compactChapterFile]: one chapter file, under the storage lock. */
    private val compactFile: (Chapter, ChapterMarkupCompactor) -> Long,
    private val stories: () -> List<Story>,
    private val compactorFor: (Story) -> ChapterMarkupCompactor?,
    private val libraryGeneration: () -> Long,
    /** Pause after each file; tests pass 0. */
    private val filePauseMs: Long = FILE_PAUSE_MS,
) {
    data class Outcome(
        val stories: Int,
        val filesChecked: Int,
        val filesCompacted: Int,
        val bytesSaved: Long,
        val finished: Boolean,
        val filesFailed: Int = 0,
    )

    private val progressFile get() = File(root, PROGRESS_FILE)

    /** Runs the pass unless it has finished before; returns null when there was nothing to do. */
    suspend fun runIfPending(): Outcome? {
        // Captured before the progress file is read, so a restore in between cannot apply the old
        // root's finished stories to the new library.
        val generation = libraryGeneration()
        val startedAt = System.currentTimeMillis()
        var outcome = Outcome(stories = 0, filesChecked = 0, filesCompacted = 0, bytesSaved = 0, finished = false)
        try {
            val done = readProgress(generation) ?: return null
            val storiesFailed = compactPending(done, generation) { outcome = it }
            val complete = storiesFailed == 0 && libraryGeneration() == generation
            // A clear or restore replaced the library, or a file failed: leave the rest for the next launch.
            if (!complete || !saveProgress(done, finished = true, generation)) {
                val type = if (outcome.filesFailed > 0) "chapter_compaction_failed" else "chapter_compaction_stopped"
                return outcome.also { record(type, it, startedAt) }
            }
            return outcome.copy(finished = true).also { record("chapter_compaction_finished", it, startedAt) }
        } catch (error: IOException) {
            // The progress file could not be read or saved: the next launch resumes from the last finished story.
            Timber.w(error, "Chapter compaction failed")
            record("chapter_compaction_failed", outcome, startedAt, "error" to error.javaClass.simpleName)
            return outcome
        }
    }

    /**
     * Compacts every story not in [done], adding each finished one to it and to the progress file.
     * [onOutcome] receives the running tally after each story. Returns how many stories had a file that failed.
     */
    private suspend fun compactPending(
        done: MutableSet<String>,
        generation: Long,
        onOutcome: (Outcome) -> Unit,
    ): Int {
        val pending = stories().filter { it.id !in done }.mapNotNull { story -> compactorFor(story)?.let { story to it } }
        var outcome = Outcome(stories = 0, filesChecked = 0, filesCompacted = 0, bytesSaved = 0, finished = false)
        var storiesFailed = 0
        for ((story, compactor) in pending) {
            val result = compactStory(story, compactor, generation, outcome) ?: break
            outcome = result.outcome
            onOutcome(outcome)
            if (result.failed > 0) {
                storiesFailed += 1
                if (storiesFailed >= MAX_FAILED_STORIES) break
                continue
            }
            done += story.id
            if (!saveProgress(done, finished = false, generation)) break
            outcome = outcome.copy(stories = outcome.stories + 1)
            onOutcome(outcome)
        }
        return storiesFailed
    }

    private class StoryResult(
        val outcome: Outcome,
        val failed: Int,
    )

    /** [outcome] plus [story]'s files; null when the library was replaced part way through. */
    private suspend fun compactStory(
        story: Story,
        compactor: ChapterMarkupCompactor,
        generation: Long,
        outcome: Outcome,
    ): StoryResult? {
        var tally = outcome
        var failed = 0
        for (chapter in story.chapters.filterNot { it.filePath.isNullOrBlank() }) {
            currentCoroutineContext().ensureActive()
            val saved =
                try {
                    // Rechecked under the lock, so no file of a library that a restore just replaced is rewritten.
                    synchronized(lock) {
                        if (libraryGeneration() != generation) return null
                        compactFile(chapter, compactor)
                    }
                } catch (error: IOException) {
                    failed += 1
                    Timber.w(error, "Could not compact a chapter file")
                    0L
                } catch (error: IllegalStateException) {
                    // AtomicFileWrites reports a refused rename this way.
                    failed += 1
                    Timber.w(error, "Could not compact a chapter file")
                    0L
                }
            tally =
                tally.copy(
                    filesChecked = tally.filesChecked + 1,
                    filesCompacted = tally.filesCompacted + if (saved > 0) 1 else 0,
                    bytesSaved = tally.bytesSaved + saved,
                )
            if (filePauseMs > 0) delay(filePauseMs) else yield()
        }
        return StoryResult(tally.copy(filesFailed = tally.filesFailed + failed), failed)
    }

    /** Finished story ids, or null when the pass has already finished or the library was just replaced. */
    private fun readProgress(generation: Long): MutableSet<String>? =
        synchronized(lock) {
            if (libraryGeneration() != generation) return@synchronized null
            val lines = progressFile.takeIf(File::isFile)?.readLines().orEmpty()
            when (lines.firstOrNull()) {
                FINISHED -> null
                // An older or unknown layout starts over; compacting a compacted file changes nothing.
                PROGRESS -> lines.drop(1).filter { it.isNotBlank() }.toMutableSet()
                else -> mutableSetOf()
            }
        }

    /** False when a clear or restore replaced the library, whose root must not get this progress. */
    private fun saveProgress(
        done: Set<String>,
        finished: Boolean,
        generation: Long,
    ): Boolean =
        synchronized(lock) {
            if (libraryGeneration() != generation) return@synchronized false
            val text = if (finished) "$FINISHED\n" else (listOf(PROGRESS) + done).joinToString("\n", postfix = "\n")
            AtomicFileWrites.writeText(progressFile, text)
            true
        }

    private fun record(
        type: String,
        outcome: Outcome,
        startedAt: Long,
        vararg extra: Pair<String, Any?>,
    ) {
        Timber.i("%s: %s", type, outcome)
        ActivityLog.record(
            ActivityCategory.LIBRARY,
            type,
            "stories" to outcome.stories,
            "filesChecked" to outcome.filesChecked,
            "filesCompacted" to outcome.filesCompacted,
            "filesFailed" to outcome.filesFailed,
            "bytesSaved" to outcome.bytesSaved,
            "ms" to System.currentTimeMillis() - startedAt,
            *extra,
        )
    }

    private companion object {
        const val PROGRESS_FILE = "chapter_compaction.txt"
        const val FINISHED = "finished v1"

        /** First line of an unfinished pass's progress file; finished story ids follow, one per line. */
        const val PROGRESS = "progress v1"

        /** Gives each rewrite's storage lock a gap other callers can take; ~3 min over 18k files. */
        const val FILE_PAUSE_MS = 10L

        /** Stops a pass that keeps failing (a full disk) instead of touching every remaining story. */
        const val MAX_FAILED_STORIES = 5
    }
}
