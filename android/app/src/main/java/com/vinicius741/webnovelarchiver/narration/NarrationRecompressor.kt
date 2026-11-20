package com.vinicius741.webnovelarchiver.narration

import com.vinicius741.webnovelarchiver.data.diagnostics.ActivityCategory
import com.vinicius741.webnovelarchiver.data.diagnostics.ActivityLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import timber.log.Timber

/**
 * Converts packs generated before compression (PCM, about 170 MB per hour of audio) to the
 * compressed clips new packs use (about 30 MB per hour), one chapter at a time, largest first.
 * Each chapter becomes a new version published only if it is still the chapter's pack
 * ([NarrationStore.recompress]), so playback, regeneration and deletion stay safe meanwhile.
 *
 * It runs once per app start in the background and waits while the generator has work
 * ([awaitIdle]). Generation starting mid-chapter ([isIdle] turning false) abandons that chapter
 * before the next clip, to be converted again once the generator is idle; killing the process
 * mid-chapter loses only that chapter's unfinished version, which the startup sweep removes, and
 * the next start carries on.
 */
internal class NarrationRecompressor(
    private val store: NarrationStore,
    private val isIdle: () -> Boolean = { true },
    private val awaitIdle: suspend () -> Unit,
) {
    private val skipped = HashSet<String>()

    // An unexpected error counts as a codec failure; cancellation still escapes.
    @Suppress("TooGenericExceptionCaught")
    suspend fun run() {
        if (!store.compresses) return
        var converted = 0
        var savedBytes = 0L
        var failuresInARow = 0
        while (failuresInARow < MAX_FAILURES_IN_A_ROW) {
            currentCoroutineContext().ensureActive()
            awaitIdle()
            val pack = next() ?: break
            val result =
                try {
                    store.recompress(pack, isIdle)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Timber.w(error, "Narration recompression failed")
                    Recompression.CodecFailed
                }
            when (result) {
                // Generation started: not this chapter's fault; it is converted once the generator is idle.
                Recompression.Interrupted -> continue
                Recompression.Superseded -> skipped += pack.dir.path
                // A damaged or odd pack stays as it is, and must not keep the packs after it from converting.
                Recompression.Unconvertible -> skipped += pack.dir.path
                Recompression.CodecFailed -> {
                    skipped += pack.dir.path
                    failuresInARow++
                }
                is Recompression.Converted -> {
                    failuresInARow = 0
                    converted++
                    savedBytes += pack.index.bytes - result.pack.index.bytes
                    ActivityLog.record(
                        ActivityCategory.TTS,
                        "narration_compressed",
                        "storyId" to pack.storyId,
                        "chapterId" to pack.chapterId,
                        "bytesBefore" to pack.index.bytes,
                        "bytesAfter" to result.pack.index.bytes,
                    )
                }
            }
            yield()
        }
        if (converted > 0 || skipped.isNotEmpty()) {
            ActivityLog.record(
                ActivityCategory.TTS,
                "narration_compression_finished",
                "chapters" to converted,
                "savedBytes" to savedBytes,
                "skipped" to skipped.size,
                "stoppedAfterFailures" to (failuresInARow >= MAX_FAILURES_IN_A_ROW),
            )
        }
    }

    /** The largest pack still holding PCM, so space comes back fastest. */
    private fun next(): NarrationPack? =
        store
            .allPacks()
            .filter { it.index.codec != NarrationStore.CODEC_AAC && it.dir.path !in skipped }
            .maxByOrNull { it.index.bytes }

    private companion object {
        /** Consecutive failures that mean the codec does not work on this phone: stop until the next start. */
        const val MAX_FAILURES_IN_A_ROW = 3
    }
}
