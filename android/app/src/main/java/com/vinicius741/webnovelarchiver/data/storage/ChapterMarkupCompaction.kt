package com.vinicius741.webnovelarchiver.data.storage

import com.vinicius741.webnovelarchiver.cleanup.ChapterMarkupCompactor
import com.vinicius741.webnovelarchiver.data.diagnostics.ActivityCategory
import com.vinicius741.webnovelarchiver.data.diagnostics.ActivityLog
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.Story
import kotlinx.coroutines.currentCoroutineContext
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
 */
internal class StoredChapterCompaction(
    /** The storage root; the progress file lives here. */
    private val root: File,
    /** The storage lock, held while progress is saved. */
    private val lock: Any,
    /** [AppStorage.compactChapterFile]: one chapter file, under the storage lock. */
    private val compactFile: (Chapter, ChapterMarkupCompactor) -> Long,
    private val stories: () -> List<Story>,
    private val compactorFor: (Story) -> ChapterMarkupCompactor?,
    private val libraryGeneration: () -> Long,
) {
    data class Outcome(
        val stories: Int,
        val filesChecked: Int,
        val filesCompacted: Int,
        val bytesSaved: Long,
        val finished: Boolean,
    )

    private val progressFile get() = File(root, PROGRESS_FILE)

    /** Runs the pass unless it has finished before; returns null when there was nothing to do. */
    suspend fun runIfPending(): Outcome? {
        val done = readProgress() ?: return null
        val generation = libraryGeneration()
        val startedAt = System.currentTimeMillis()
        val pending = stories().filter { it.id !in done }.mapNotNull { story -> compactorFor(story)?.let { story to it } }
        var outcome = Outcome(stories = 0, filesChecked = 0, filesCompacted = 0, bytesSaved = 0, finished = false)
        try {
            for ((story, compactor) in pending) {
                outcome = compactStory(story, compactor, generation, outcome) ?: break
                done += story.id
                if (!saveProgress(done, finished = false, generation)) break
                outcome = outcome.copy(stories = outcome.stories + 1)
            }
            // A clear or restore replaced the library: stop and leave the new one for the next launch.
            if (libraryGeneration() != generation || !saveProgress(done, finished = true, generation)) {
                return outcome.also { record("chapter_compaction_stopped", it, startedAt) }
            }
            return outcome.copy(finished = true).also { record("chapter_compaction_finished", it, startedAt) }
        } catch (error: IOException) {
            // Disk full or an unreadable file: the next launch resumes from the last finished story.
            Timber.w(error, "Chapter compaction failed")
            record("chapter_compaction_failed", outcome, startedAt, "error" to error.javaClass.simpleName)
            return outcome
        }
    }

    /** [outcome] plus [story]'s files; null when the library was replaced part way through. */
    private suspend fun compactStory(
        story: Story,
        compactor: ChapterMarkupCompactor,
        generation: Long,
        outcome: Outcome,
    ): Outcome? {
        var tally = outcome
        for (chapter in story.chapters.filterNot { it.filePath.isNullOrBlank() }) {
            currentCoroutineContext().ensureActive()
            if (libraryGeneration() != generation) return null
            val saved = compactFile(chapter, compactor)
            tally =
                tally.copy(
                    filesChecked = tally.filesChecked + 1,
                    filesCompacted = tally.filesCompacted + if (saved > 0) 1 else 0,
                    bytesSaved = tally.bytesSaved + saved,
                )
            yield()
        }
        return tally
    }

    /** Finished story ids, or null when the pass has already finished. */
    private fun readProgress(): MutableSet<String>? {
        val lines = progressFile.takeIf(File::isFile)?.readLines().orEmpty()
        if (lines.firstOrNull() == FINISHED) return null
        return lines.filter { it.isNotBlank() }.toMutableSet()
    }

    /** False when a clear or restore replaced the library, whose root must not get this progress. */
    private fun saveProgress(
        done: Set<String>,
        finished: Boolean,
        generation: Long,
    ): Boolean =
        synchronized(lock) {
            if (libraryGeneration() != generation) return@synchronized false
            val text = if (finished) "$FINISHED\n" else done.joinToString("\n", postfix = "\n")
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
            "bytesSaved" to outcome.bytesSaved,
            "ms" to System.currentTimeMillis() - startedAt,
            *extra,
        )
    }

    private companion object {
        const val PROGRESS_FILE = "chapter_compaction.txt"
        const val FINISHED = "finished v1"
    }
}
