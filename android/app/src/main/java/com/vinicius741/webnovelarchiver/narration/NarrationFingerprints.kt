package com.vinicius741.webnovelarchiver.narration

import com.vinicius741.webnovelarchiver.data.storage.AtomicFileWrites
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import timber.log.Timber
import java.io.File

/**
 * Text fingerprints of chapters that have audio, so a chapter list can tell ready audio from audio
 * whose text changed without preparing every chapter's text each time it opens.
 *
 * A fingerprint is reused while the chapter's stamp (its stored text's size and modification time,
 * download time and the cleanup rules) is unchanged. They are kept in memory and in [file], a
 * disposable cache, so they survive app restarts; missing ones are computed [parallelism] at a time.
 * Only screens read these: playback always checks the text it prepared itself.
 */
internal class NarrationFingerprints(
    private val file: File?,
    /** The app build: text preparation can change between builds, so a file from another one is ignored. */
    private val build: String = "",
    private val parallelism: Int = DEFAULT_PARALLELISM,
) {
    private class Entry(
        val stamp: String,
        val fingerprint: String,
    )

    private val lock = Any()
    private val entries = HashMap<Pair<String, String>, Entry>()
    private var loaded = false
    private var unsaved = false
    private val saving = Mutex()
    private val header get() = "$HEADER $build"

    /**
     * Fingerprints of [chapterIds] of [storyId], by chapter id; null for a chapter without text.
     * [stampOf] is cheap (no text read) and null when the chapter has no text; [compute] reads and
     * prepares the text. [keep] says which entries are still worth saving (chapters that have audio).
     */
    suspend fun of(
        storyId: String,
        chapterIds: List<String>,
        stampOf: (String) -> String?,
        compute: suspend (String) -> String?,
        keep: (storyId: String, chapterId: String) -> Boolean,
    ): Map<String, String?> {
        load()
        val result = HashMap<String, String?>()
        val missing = ArrayList<Pair<String, String>>()
        for (chapterId in chapterIds) {
            val stamp = stampOf(chapterId)
            val cached = synchronized(lock) { entries[storyId to chapterId] }
            when {
                stamp == null -> result[chapterId] = null
                cached != null && cached.stamp == stamp -> result[chapterId] = cached.fingerprint
                else -> missing += chapterId to stamp
            }
        }
        val permits = Semaphore(parallelism)
        // Each result is kept as soon as it is known, so work a cancelled call finished is not lost.
        val computed =
            coroutineScope {
                missing
                    .map { (chapterId, stamp) ->
                        async {
                            permits.withPermit {
                                val fingerprint = compute(chapterId)
                                if (fingerprint != null) {
                                    synchronized(lock) {
                                        entries[storyId to chapterId] = Entry(stamp, fingerprint)
                                        unsaved = true
                                    }
                                }
                                chapterId to fingerprint
                            }
                        }
                    }.awaitAll()
            }
        computed.forEach { (chapterId, fingerprint) -> result[chapterId] = fingerprint }
        if (synchronized(lock) { unsaved }) save(keep)
        return result
    }

    private fun load() {
        synchronized(lock) {
            if (loaded) return
            loaded = true
            val lines = runCatching { file?.takeIf(File::isFile)?.readLines() }.getOrNull() ?: return
            if (lines.firstOrNull() != header) return
            for (line in lines.drop(1)) {
                val parts = line.split(SEPARATOR)
                if (parts.size != FIELDS) continue
                entries[parts[0] to parts[1]] = Entry(parts[2], parts[3])
            }
        }
    }

    private suspend fun save(keep: (String, String) -> Boolean) {
        val target = file ?: return
        saving.withLock {
            val text =
                synchronized(lock) {
                    unsaved = false
                    entries.keys.retainAll { (storyId, chapterId) -> keep(storyId, chapterId) }
                    buildString {
                        append(header).append('\n')
                        for ((key, entry) in entries) {
                            val fields = listOf(key.first, key.second, entry.stamp, entry.fingerprint)
                            if (fields.any { SEPARATOR in it || '\n' in it }) continue
                            fields.joinTo(this, SEPARATOR).append('\n')
                        }
                    }
                }
            runCatching {
                target.parentFile?.mkdirs()
                AtomicFileWrites.writeText(target, text)
            }.onFailure { Timber.w(it, "Could not save narration fingerprints") }
        }
    }

    companion object {
        private const val HEADER = "narration-fingerprints 1"
        private const val SEPARATOR = "\t"
        private const val FIELDS = 4
        private val DEFAULT_PARALLELISM = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
    }
}
