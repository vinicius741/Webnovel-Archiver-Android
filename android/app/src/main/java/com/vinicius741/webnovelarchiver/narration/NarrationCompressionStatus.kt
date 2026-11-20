package com.vinicius741.webnovelarchiver.narration

/**
 * Where the background conversion of uncompressed (PCM) packs stands, for the screens that show
 * it ([NarrationRecompressor.status]). The counts cover this app start; [remainingChapters] and
 * [remainingBytes] are what still holds PCM on disk.
 */
internal data class NarrationCompressionStatus(
    val phase: Phase = Phase.CHECKING,
    val remainingChapters: Int = 0,
    val remainingBytes: Long = 0,
    val convertedChapters: Int = 0,
    /** Size of the converted chapters before conversion, so the ratio seen so far can be told. */
    val convertedBytesBefore: Long = 0,
    val savedBytes: Long = 0,
    /** Chapters given up on for this app start (damaged, odd sample rate, or the codec kept failing). */
    val failedChapters: Int = 0,
    /** While [Phase.PAUSED]: when the next attempt starts (epoch ms). */
    val retryAt: Long? = null,
) {
    enum class Phase {
        /** Not started yet (the library is still loading). */
        CHECKING,

        /** Converting a chapter. */
        COMPRESSING,

        /** Generation has work; conversion waits for it. */
        WAITING_FOR_GENERATION,

        /** The codec failed several times in a row; it tries again at [retryAt]. */
        PAUSED,

        /** Nothing left this start could convert. */
        DONE,
    }

    /** Share of this start's work done, 0..1 (by PCM bytes); null before any work. */
    val fraction: Float?
        get() {
            val total = convertedBytesBefore + remainingBytes
            return if (total <= 0) null else (convertedBytesBefore.toFloat() / total)
        }

    /** What the audio will take once every remaining chapter is converted, from [totalBytes] now. */
    fun expectedFinalBytes(totalBytes: Long): Long {
        val ratio = if (convertedBytesBefore > 0) (convertedBytesBefore - savedBytes).toDouble() / convertedBytesBefore else TYPICAL_RATIO
        return totalBytes - (remainingBytes * (1 - ratio)).toLong()
    }

    /** Whether there is anything worth telling the user. */
    val visible: Boolean
        get() = phase != Phase.CHECKING && (phase != Phase.DONE || convertedChapters > 0 || failedChapters > 0)

    companion object {
        /** AAC 64k against 24 kHz PCM, as measured on the owner's phone (about 17.5%). */
        const val TYPICAL_RATIO = 0.175
    }
}
