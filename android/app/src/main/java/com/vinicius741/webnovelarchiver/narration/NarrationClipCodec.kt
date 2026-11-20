package com.vinicius741.webnovelarchiver.narration

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import com.vinicius741.webnovelarchiver.tts.BYTES_PER_FRAME
import com.vinicius741.webnovelarchiver.tts.TtsPcmClip
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Compresses pack clips and restores them; [NarrationStore] stores what it returns. */
internal interface NarrationClipCodec {
    /** The clip compressed, or null when this codec cannot take it (an unsupported sample rate, no encoder). */
    fun encode(clip: TtsPcmClip): NarrationEncodedClip?

    /** The clip's PCM, exactly [NarrationEncodedClip.frames] long; null when it cannot be decoded. */
    fun decode(clip: NarrationEncodedClip): ByteArray?
}

/**
 * AAC-LC at 64 kbit/s through Android's own codecs. Measured on the app's Kokoro audio, it is as
 * good as the PCM it replaces (ViSQOL 4.72 of 4.73) at a fifth of the size; see
 * `docs/tts/narration-audio-compression.md`. The platform software codecs (`c2.android.aac.*`, the
 * Fraunhofer encoder that measurement used) are preferred over vendor ones so every phone stores
 * the same audio.
 *
 * Android reports no encoder delay, so it is measured once per sample rate by passing a known
 * signal through both codecs, and saved in every clip so decoding never depends on it again.
 */
internal class AacNarrationCodec(
    private val bitrate: Int = BITRATE,
) : NarrationClipCodec {
    /** Measured delay per sample rate; null once a measurement failed, so it is not retried every clip. */
    private val delays = HashMap<Int, Int?>()

    override fun encode(clip: TtsPcmClip): NarrationEncodedClip? {
        if (clip.sampleRate !in SAMPLE_RATES) return null
        if (clip.pcm.isEmpty()) {
            return NarrationEncodedClip(
                NarrationClipFormat.CODEC_AAC_LC,
                clip.sampleRate,
                0,
                0,
                ByteArray(0),
                emptyList(),
            )
        }
        val delay = delayFor(clip.sampleRate) ?: return null
        val (setup, units) = encodeRaw(clip.sampleRate, clip.pcm)
        val encoded = NarrationEncodedClip(NarrationClipFormat.CODEC_AAC_LC, clip.sampleRate, clip.frames, delay, setup, units)
        if (!NarrationClipFormat.covers(encoded)) {
            Timber.w("AAC encoder dropped the end of a clip (%d units for %d frames)", units.size, clip.frames)
            return null
        }
        return encoded
    }

    override fun decode(clip: NarrationEncodedClip): ByteArray? {
        if (clip.codec != NarrationClipFormat.CODEC_AAC_LC) return null
        if (clip.frames == 0) return ByteArray(0)
        return runCatching {
            NarrationClipFormat.trim(decodeRaw(clip.sampleRate, clip.setup, clip.units), clip.delayFrames, clip.frames)
        }.onFailure { Timber.w(it, "AAC clip decode failed") }.getOrNull()
    }

    /** The encoder-plus-decoder delay at [sampleRate], measured once per process; null when the codecs fail. */
    @Synchronized
    private fun delayFor(sampleRate: Int): Int? =
        delays.getOrPut(sampleRate) {
            runCatching { measureDelay(sampleRate) }
                .onFailure { Timber.w(it, "AAC delay measurement failed at %d Hz", sampleRate) }
                .getOrNull()
        }

    private fun measureDelay(sampleRate: Int): Int {
        val probe = NarrationCodecProbe.signal(PROBE_FRAMES)
        val (setup, units) = encodeRaw(sampleRate, probe)
        val decoded = decodeRaw(sampleRate, setup, units)
        return NarrationCodecProbe.delay(probe, decoded, MAX_DELAY_FRAMES) ?: throw IOException("AAC delay not found")
    }

    private fun encodeRaw(
        sampleRate: Int,
        pcm: ByteArray,
    ): Pair<ByteArray, List<ByteArray>> {
        val format =
            MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CHUNK_BYTES)
            }
        val codec = create(ENCODER, encoder = true, format)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            var setup: ByteArray? = null
            val units = ArrayList<ByteArray>()
            run(codec, PcmFeed(pcm, sampleRate)) { buffer, info, outputFormat ->
                when {
                    outputFormat != null -> setup = setup ?: outputFormat.getByteBuffer(CSD)?.let(::bytesOf)
                    info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 -> setup = bytesOf(buffer!!)
                    info.size > 0 -> units += bytesOf(buffer!!)
                }
            }
            return (setup ?: throw IOException("AAC encoder gave no setup")) to units
        } finally {
            codec.release()
        }
    }

    private fun decodeRaw(
        sampleRate: Int,
        setup: ByteArray,
        units: List<ByteArray>,
    ): ByteArray {
        val format =
            MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
                setByteBuffer(CSD, ByteBuffer.wrap(setup))
                setInteger(MediaFormat.KEY_IS_ADTS, 0)
            }
        val codec = create(DECODER, encoder = false, format)
        try {
            codec.configure(format, null, null, 0)
            codec.start()
            val out = PcmCollector(sampleRate)
            run(codec, UnitFeed(units, sampleRate), out::accept)
            return out.bytes.toByteArray()
        } finally {
            codec.release()
        }
    }

    /** Gathers a decoder's PCM, keeping the first channel should it ever decode mono to more. */
    private inner class PcmCollector(
        private val sampleRate: Int,
    ) {
        val bytes = ByteArrayOutputStream()
        private var channels = 1

        fun accept(
            buffer: ByteBuffer?,
            info: MediaCodec.BufferInfo,
            outputFormat: MediaFormat?,
        ) {
            if (outputFormat != null) {
                channels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                val rate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                if (rate != sampleRate) throw IOException("AAC decoder changed the sample rate to $rate")
            } else if (info.size > 0 && buffer != null) {
                bytes.write(firstChannel(bytesOf(buffer), channels))
            }
        }
    }

    /** What a codec is fed: [fill] writes the next input into a codec buffer and returns its time, or null at the end. */
    private interface Feed {
        val endTimeUs: Long

        fun fill(buffer: ByteBuffer): Long?
    }

    /** PCM in pieces as large as the codec's buffers take, whole frames each. */
    private class PcmFeed(
        private val pcm: ByteArray,
        private val sampleRate: Int,
    ) : Feed {
        private var offset = 0
        override val endTimeUs: Long get() = pcm.size / BYTES_PER_FRAME * MICROS / sampleRate

        override fun fill(buffer: ByteBuffer): Long? {
            if (offset >= pcm.size) return null
            val room = minOf(CHUNK_BYTES, buffer.remaining()) / BYTES_PER_FRAME * BYTES_PER_FRAME
            if (room <= 0) throw IOException("AAC input buffer too small")
            val size = minOf(room, pcm.size - offset)
            val timeUs = offset / BYTES_PER_FRAME * MICROS / sampleRate
            buffer.put(pcm, offset, size)
            offset += size
            return timeUs
        }
    }

    /** Encoded access units, one per codec buffer. */
    private class UnitFeed(
        private val units: List<ByteArray>,
        private val sampleRate: Int,
    ) : Feed {
        private var next = 0
        override val endTimeUs: Long get() = units.size * AAC_FRAME * MICROS / sampleRate

        override fun fill(buffer: ByteBuffer): Long? {
            val unit = units.getOrNull(next) ?: return null
            if (unit.size > buffer.remaining()) throw IOException("AAC input buffer too small")
            buffer.put(unit)
            return next++ * AAC_FRAME * MICROS / sampleRate
        }
    }

    /**
     * Feeds [feed] then end of stream, handing each output (or a format change, with its format)
     * to [onOutput] until the codec signals end of stream.
     *
     * Each round queues at most one input without waiting, then takes every output ready. It waits
     * (for output, which wakes it as soon as any arrives) only when it could not queue anything.
     * Taking one output per round and waiting on input instead left the codec stalled on full
     * output buffers for the whole wait, about 10 ms per AAC frame.
     *
     * A stall is [MAX_IDLE_ROUNDS] rounds in a row with nothing queued or taken. Counting rounds
     * rather than time keeps a process Android froze mid-clip from failing when it thaws.
     */
    private fun run(
        codec: MediaCodec,
        feed: Feed,
        onOutput: (ByteBuffer?, MediaCodec.BufferInfo, MediaFormat?) -> Unit,
    ) {
        val info = MediaCodec.BufferInfo()
        var endQueued = false
        var idleRounds = 0
        while (true) {
            if (idleRounds > MAX_IDLE_ROUNDS) throw IOException("AAC codec stalled")
            var queued = false
            if (!endQueued) {
                val index = codec.dequeueInputBuffer(0)
                if (index >= 0) {
                    endQueued = queue(codec, index, feed)
                    queued = true
                }
            }
            val taken = drain(codec, info, if (queued) 0 else WAIT_US, onOutput) ?: return
            idleRounds = if (queued || taken > 0) 0 else idleRounds + 1
        }
    }

    /**
     * Hands every output ready (waiting up to [waitUs] for the first) to [onOutput]. Returns how
     * many it took, or null at end of stream.
     */
    private fun drain(
        codec: MediaCodec,
        info: MediaCodec.BufferInfo,
        waitUs: Long,
        onOutput: (ByteBuffer?, MediaCodec.BufferInfo, MediaFormat?) -> Unit,
    ): Int? {
        var taken = 0
        var index = codec.dequeueOutputBuffer(info, waitUs)
        while (index != MediaCodec.INFO_TRY_AGAIN_LATER) {
            taken++
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                onOutput(null, info, codec.outputFormat)
            } else if (index >= 0) {
                val buffer = codec.getOutputBuffer(index)
                buffer?.position(info.offset)
                buffer?.limit(info.offset + info.size)
                onOutput(buffer, info, null)
                codec.releaseOutputBuffer(index, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return null
            }
            index = codec.dequeueOutputBuffer(info, 0)
        }
        return taken
    }

    /** Fills input buffer [index] from [feed] and queues it; true when that was the end of stream. */
    private fun queue(
        codec: MediaCodec,
        index: Int,
        feed: Feed,
    ): Boolean {
        val buffer = codec.getInputBuffer(index) ?: throw IOException("No AAC input buffer")
        buffer.clear()
        val timeUs = feed.fill(buffer)
        if (timeUs == null) {
            codec.queueInputBuffer(index, 0, 0, feed.endTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            return true
        }
        codec.queueInputBuffer(index, 0, buffer.position(), timeUs, 0)
        return false
    }

    private fun create(
        preferred: String,
        encoder: Boolean,
        format: MediaFormat,
    ): MediaCodec {
        runCatching { return MediaCodec.createByCodecName(preferred) }
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val name = if (encoder) list.findEncoderForFormat(format) else list.findDecoderForFormat(format)
        return name?.let(MediaCodec::createByCodecName) ?: throw IOException("No AAC ${if (encoder) "encoder" else "decoder"}")
    }

    companion object {
        const val BITRATE = 64_000
        private const val ENCODER = "c2.android.aac.encoder"
        private const val DECODER = "c2.android.aac.decoder"
        private const val CSD = "csd-0"

        /** The rates AAC-LC defines (and Android's encoder takes). */
        private val SAMPLE_RATES = setOf(8_000, 11_025, 12_000, 16_000, 22_050, 24_000, 32_000, 44_100, 48_000)
        private const val CHUNK_BYTES = 8_192
        private const val AAC_FRAME = NarrationClipFormat.AAC_FRAME_SAMPLES.toLong()
        private const val MICROS = 1_000_000L
        private const val WAIT_US = 2_000L

        /** About five seconds of waiting with nothing moving. */
        private const val MAX_IDLE_ROUNDS = 2_500
        private const val PROBE_FRAMES = 12_000
        private const val MAX_DELAY_FRAMES = 6_000
    }
}

private fun bytesOf(buffer: ByteBuffer): ByteArray = ByteArray(buffer.remaining()).also { buffer.get(it) }

private fun firstChannel(
    pcm: ByteArray,
    channels: Int,
): ByteArray {
    if (channels <= 1) return pcm
    val stride = channels * BYTES_PER_FRAME
    val out = ByteArray(pcm.size / stride * BYTES_PER_FRAME)
    for (frame in 0 until pcm.size / stride) {
        out[frame * BYTES_PER_FRAME] = pcm[frame * stride]
        out[frame * BYTES_PER_FRAME + 1] = pcm[frame * stride + 1]
    }
    return out
}

/** The known signal the codec delay is measured with, and the search for it in the decoded copy. */
internal object NarrationCodecProbe {
    /** Deterministic noise at about a fifth of full scale: broadband, so its correlation peak is sharp. */
    fun signal(frames: Int): ByteArray {
        val pcm = ByteBuffer.allocate(frames * BYTES_PER_FRAME).order(ByteOrder.LITTLE_ENDIAN)
        var state = SEED
        repeat(frames) {
            state = state * MULTIPLIER + INCREMENT
            pcm.putShort(((state ushr SHIFT).toInt() % (2 * AMPLITUDE) - AMPLITUDE).toShort())
        }
        return pcm.array()
    }

    /** The lag (0..[maxLag]) at which [decoded] best matches [probe]; null when nothing matches. */
    fun delay(
        probe: ByteArray,
        decoded: ByteArray,
        maxLag: Int,
    ): Int? {
        val x = samples(probe)
        val y = samples(decoded)
        val start = x.size / WINDOW_START_DIVISOR
        val length = minOf(WINDOW_FRAMES, x.size - start)
        var best = Double.NEGATIVE_INFINITY
        var bestLag = -1
        for (lag in 0..maxLag) {
            if (start + lag + length > y.size) break
            var sum = 0.0
            for (i in 0 until length) sum += x[start + i].toDouble() * y[start + lag + i]
            if (sum > best) {
                best = sum
                bestLag = lag
            }
        }
        return bestLag.takeIf { it >= 0 && best > 0 }
    }

    private fun samples(pcm: ByteArray): ShortArray {
        val out = ShortArray(pcm.size / BYTES_PER_FRAME)
        ByteBuffer
            .wrap(pcm)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(out)
        return out
    }

    private const val SEED = 0x2545F4914F6CDD1DL
    private const val MULTIPLIER = 6364136223846793005L
    private const val INCREMENT = 1442695040888963407L
    private const val SHIFT = 33
    private const val AMPLITUDE = 6_000
    private const val WINDOW_START_DIVISOR = 4
    private const val WINDOW_FRAMES = 4_096
}
