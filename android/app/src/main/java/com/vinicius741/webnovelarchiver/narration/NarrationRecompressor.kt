package com.vinicius741.webnovelarchiver.narration

import android.media.MediaCodec
import com.vinicius741.webnovelarchiver.data.diagnostics.ActivityCategory
import com.vinicius741.webnovelarchiver.data.diagnostics.ActivityLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.yield
import timber.log.Timber

/**
 * Converts packs generated before compression (PCM, about 170 MB per hour of audio) to the
 * compressed clips new packs use (about 30 MB per hour), one chapter at a time, largest first.
 * Each chapter becomes a new version published only if it is still the chapter's pack
 * ([NarrationStore.recompress]), so playback, regeneration and deletion stay safe meanwhile.
 *
 * It runs in the background from app start until nothing is left, and waits while the generator
 * has work ([awaitIdle]). Generation starting mid-chapter ([isIdle] turning false) abandons that
 * chapter before the next clip, to be converted again once the generator is idle; killing the
 * process mid-chapter loses only that chapter's unfinished version, which the startup sweep
 * removes, and the next start carries on.
 *
 * Codec failures are often passing (Android reclaims codecs from background apps, the media
 * process restarts), so they pause the pass instead of ending it: after [MAX_FAILURES_IN_A_ROW]
 * in a row it waits ([PAUSES_MS], growing) and tries again, failed chapters included. A chapter
 * is given up on (until the next app start) only after [MAX_ATTEMPTS_PER_CHAPTER] failures.
 * [status] tells the screens where it stands.
 */
internal class NarrationRecompressor(
    private val store: NarrationStore,
    private val isIdle: () -> Boolean = { true },
    private val now: () -> Long = System::currentTimeMillis,
    private val awaitIdle: suspend () -> Unit,
) {
    /** Given up on for this start. */
    private val skipped = HashSet<String>()

    /** Failed this round; tried again after the next pause. */
    private val deferred = HashSet<String>()
    private val attempts = HashMap<String, Int>()

    private val state = MutableStateFlow(NarrationCompressionStatus())
    val status: StateFlow<NarrationCompressionStatus> = state.asStateFlow()

    // An unexpected error counts as a codec failure; cancellation still escapes.
    @Suppress("TooGenericExceptionCaught", "LongMethod", "CyclomaticComplexMethod")
    suspend fun run() {
        if (!store.compresses) {
            state.value = NarrationCompressionStatus(NarrationCompressionStatus.Phase.DONE)
            return
        }
        var failuresInARow = 0
        var pauses = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            if (!isIdle()) publish(NarrationCompressionStatus.Phase.WAITING_FOR_GENERATION)
            awaitIdle()
            val pack = next()
            if (pack == null && deferred.isEmpty()) break
            if (pack == null || failuresInARow >= MAX_FAILURES_IN_A_ROW) {
                val pauseMs = PAUSES_MS[minOf(pauses, PAUSES_MS.lastIndex)]
                pauses++
                failuresInARow = 0
                publish(NarrationCompressionStatus.Phase.PAUSED, retryAt = now() + pauseMs)
                ActivityLog.record(
                    ActivityCategory.TTS,
                    "narration_compression_paused",
                    "pauseMs" to pauseMs,
                    "retrying" to deferred.size,
                    "remaining" to state.value.remainingChapters,
                )
                delay(pauseMs)
                deferred.clear()
                continue
            }
            publish(NarrationCompressionStatus.Phase.COMPRESSING)
            val result =
                try {
                    store.recompress(pack, isIdle)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    recordFailure(pack, error)
                    Recompression.CodecFailed
                }
            when (result) {
                // Generation started: not this chapter's fault; it is converted once the generator is idle.
                Recompression.Interrupted -> Unit
                Recompression.Superseded -> skipped += pack.dir.path
                // A damaged or odd pack stays as it is, and must not keep the packs after it from converting.
                Recompression.Unconvertible -> giveUp(pack)
                Recompression.CodecFailed -> {
                    failuresInARow++
                    val tries = (attempts[pack.dir.path] ?: 0) + 1
                    attempts[pack.dir.path] = tries
                    if (tries >= MAX_ATTEMPTS_PER_CHAPTER) giveUp(pack) else deferred += pack.dir.path
                }
                is Recompression.Converted -> {
                    failuresInARow = 0
                    pauses = 0
                    val saved = pack.index.bytes - result.pack.index.bytes
                    state.update {
                        it.copy(
                            convertedChapters = it.convertedChapters + 1,
                            convertedBytesBefore = it.convertedBytesBefore + pack.index.bytes,
                            savedBytes = it.savedBytes + saved,
                        )
                    }
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
        publish(NarrationCompressionStatus.Phase.DONE)
        val done = state.value
        if (done.convertedChapters > 0 || done.failedChapters > 0) {
            ActivityLog.record(
                ActivityCategory.TTS,
                "narration_compression_finished",
                "chapters" to done.convertedChapters,
                "savedBytes" to done.savedBytes,
                "failed" to done.failedChapters,
                "remaining" to done.remainingChapters,
            )
        }
    }

    private fun giveUp(pack: NarrationPack) {
        if (skipped.add(pack.dir.path)) state.update { it.copy(failedChapters = it.failedChapters + 1) }
    }

    /** Logs a failure with what Android says about it (a reclaimed or crashed codec is passing). */
    private fun recordFailure(
        pack: NarrationPack,
        error: Exception,
    ) {
        val codec = error as? MediaCodec.CodecException
        val detail =
            codec?.let { "code=${it.errorCode} transient=${it.isTransient} recoverable=${it.isRecoverable} ${it.diagnosticInfo}" }
                ?: error.toString()
        Timber.w(error, "Narration recompression failed for %s/%s: %s", pack.storyId, pack.chapterId, detail)
        ActivityLog.record(
            ActivityCategory.TTS,
            "narration_compression_failed",
            "storyId" to pack.storyId,
            "chapterId" to pack.chapterId,
            "bytes" to pack.index.bytes,
            "error" to detail,
        )
    }

    /** Recounts what still holds PCM and publishes [phase]. */
    private fun publish(
        phase: NarrationCompressionStatus.Phase,
        retryAt: Long? = null,
    ) {
        val pcm = store.allPacks().filter { it.index.codec != NarrationStore.CODEC_AAC }
        state.update {
            it.copy(
                phase = phase,
                remainingChapters = pcm.size,
                remainingBytes = pcm.sumOf { pack -> pack.index.bytes },
                retryAt = retryAt,
            )
        }
    }

    /** The largest pack still holding PCM, so space comes back fastest. */
    private fun next(): NarrationPack? =
        store
            .allPacks()
            .filter { it.index.codec != NarrationStore.CODEC_AAC && it.dir.path !in skipped && it.dir.path !in deferred }
            .maxByOrNull { it.index.bytes }

    private companion object {
        /** Consecutive failures that pause the pass. */
        const val MAX_FAILURES_IN_A_ROW = 3

        /** Failures after which a chapter is left as PCM until the next app start. */
        const val MAX_ATTEMPTS_PER_CHAPTER = 3

        /** Pauses after failures: 1 minute, 5, 15, then hourly. */
        val PAUSES_MS = longArrayOf(60_000L, 300_000L, 900_000L, 3_600_000L)
    }
}
