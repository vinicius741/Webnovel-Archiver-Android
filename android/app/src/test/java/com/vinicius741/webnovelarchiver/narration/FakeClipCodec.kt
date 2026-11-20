package com.vinicius741.webnovelarchiver.narration

import com.vinicius741.webnovelarchiver.tts.TtsPcmClip

/**
 * A lossless stand-in for the AAC codec (MediaCodec does not run in unit tests): its "units" are
 * the PCM itself in 1024-frame pieces, after [delay] frames of silence, like a real codec's start-up delay.
 */
internal class FakeClipCodec(
    private val delay: Int = 3,
) : NarrationClipCodec {
    var encodes = 0
    var refuse = false

    /** Refuses this many encodes, then works: a codec failing for a while, as a reclaimed one does. */
    var refuseNext = 0
    var onEncode: () -> Unit = {}
    var sampleRates: Set<Int>? = null

    override fun supports(sampleRate: Int): Boolean = sampleRates?.contains(sampleRate) ?: true

    override fun encode(clip: TtsPcmClip): NarrationEncodedClip? {
        if (refuse || !supports(clip.sampleRate)) return null
        if (refuseNext > 0) {
            refuseNext--
            return null
        }
        encodes++
        onEncode()
        val audio = ByteArray(delay * 2) + clip.pcm
        // Like AAC: whole 1024-frame units, the last one padded with silence.
        val stream = audio.copyOf((audio.size + UNIT_BYTES - 1) / UNIT_BYTES * UNIT_BYTES)
        val units = (stream.indices step UNIT_BYTES).map { stream.copyOfRange(it, it + UNIT_BYTES) }
        return NarrationEncodedClip(NarrationClipFormat.CODEC_AAC_LC, clip.sampleRate, clip.frames, delay, byteArrayOf(0x12, 0x08), units)
    }

    override fun decode(clip: NarrationEncodedClip): ByteArray =
        NarrationClipFormat.trim(clip.units.fold(ByteArray(0)) { all, unit -> all + unit }, clip.delayFrames, clip.frames)

    private companion object {
        const val UNIT_BYTES = NarrationClipFormat.AAC_FRAME_SAMPLES * 2
    }
}
