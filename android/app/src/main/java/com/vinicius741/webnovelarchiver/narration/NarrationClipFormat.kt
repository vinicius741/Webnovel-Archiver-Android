package com.vinicius741.webnovelarchiver.narration

import com.vinicius741.webnovelarchiver.tts.BYTES_PER_FRAME
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * How one sentence of a pack is stored on disk. Two layouts exist:
 *
 * - **Legacy PCM** (packs made before compression): a 4-byte little-endian sample rate, then 16-bit
 *   mono PCM.
 * - **Compressed** (`WNAC`): a header naming the codec, the sample rate, the exact PCM length and
 *   the codec's start delay, its setup bytes, then each encoded access unit with a 4-byte length.
 *   Decoding drops [NarrationEncodedClip.delayFrames] and keeps exactly [NarrationEncodedClip.frames],
 *   so a clip plays and times exactly as the PCM it was made from.
 *
 * The magic `WNAC` read as a little-endian int is about 1.1 billion, never a legacy sample rate,
 * so the first four bytes tell the layouts apart.
 */
internal object NarrationClipFormat {
    const val LEGACY_HEADER_BYTES = 4
    const val CODEC_AAC_LC: Int = 1
    private const val VERSION: Int = 1
    private val MAGIC = byteArrayOf('W'.code.toByte(), 'N'.code.toByte(), 'A'.code.toByte(), 'C'.code.toByte())

    /** magic, version, codec, sample rate, frames, delay, setup length. */
    private const val FIXED_HEADER_BYTES = 4 + 1 + 1 + 4 + 4 + 4 + 2
    private const val UNIT_LENGTH_BYTES = 4
    private const val MAX_SAMPLE_RATE = 384_000

    /** PCM frames one AAC-LC access unit decodes to. */
    const val AAC_FRAME_SAMPLES = 1_024

    fun isCompressed(head: ByteArray): Boolean = head.size >= MAGIC.size && MAGIC.indices.all { head[it] == MAGIC[it] }

    fun legacy(
        sampleRate: Int,
        pcm: ByteArray,
    ): ByteArray =
        ByteBuffer
            .allocate(LEGACY_HEADER_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(sampleRate)
            .array() + pcm

    fun write(clip: NarrationEncodedClip): ByteArray {
        require(clip.setup.size <= UShort.MAX_VALUE.toInt()) { "Codec setup too large" }
        val out = ByteArrayOutputStream(FIXED_HEADER_BYTES + clip.setup.size + clip.units.sumOf { it.size + UNIT_LENGTH_BYTES })
        val header =
            ByteBuffer
                .allocate(FIXED_HEADER_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put(MAGIC)
                .put(VERSION.toByte())
                .put(clip.codec.toByte())
                .putInt(clip.sampleRate)
                .putInt(clip.frames)
                .putInt(clip.delayFrames)
                .putShort(clip.setup.size.toShort())
        out.write(header.array())
        out.write(clip.setup)
        val length = ByteBuffer.allocate(UNIT_LENGTH_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        clip.units.forEach { unit ->
            length.clear()
            out.write(length.putInt(unit.size).array())
            out.write(unit)
        }
        return out.toByteArray()
    }

    /**
     * The compressed clip in [bytes], or null when they are not a whole, valid one. Its units must
     * cover the delay and every frame ([covers]), so a damaged count never decodes as silence.
     */
    fun read(bytes: ByteArray): NarrationEncodedClip? {
        val header = header(bytes, bytes.size.toLong()) ?: return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(FIXED_HEADER_BYTES)
        val setup = ByteArray(header.setupSize).also(buffer::get)
        val units = ArrayList<ByteArray>()
        while (buffer.hasRemaining()) {
            if (buffer.remaining() < UNIT_LENGTH_BYTES) return null
            val size = buffer.int
            if (size < 0 || size > buffer.remaining()) return null
            units += ByteArray(size).also(buffer::get)
        }
        val clip = NarrationEncodedClip(header.codec, header.sampleRate, header.frames, header.delay, setup, units)
        return clip.takeIf(::covers)
    }

    /**
     * Whether [clip]'s units decode to at least its delay plus its frames. An encoder that dropped
     * the end of a sentence fails this, and the clip is stored as PCM instead.
     */
    fun covers(clip: NarrationEncodedClip): Boolean =
        clip.frames == 0 || clip.codec != CODEC_AAC_LC ||
            clip.units.size.toLong() * AAC_FRAME_SAMPLES >= clip.delayFrames.toLong() + clip.frames

    /** Sample rate and length of the clip in [file], read from its header alone; null when unreadable. */
    fun info(file: File): NarrationClipInfo? =
        runCatching {
            RandomAccessFile(file, "r").use { input ->
                val length = input.length()
                if (length < LEGACY_HEADER_BYTES) return null
                val head = ByteArray(minOf(length, FIXED_HEADER_BYTES.toLong()).toInt())
                input.readFully(head)
                if (isCompressed(head)) {
                    val header = header(head, length) ?: return null
                    NarrationClipInfo(header.sampleRate, header.frames.toLong(), compressed = true, codec = header.codec)
                } else {
                    val frames = (length - LEGACY_HEADER_BYTES) / BYTES_PER_FRAME
                    val sampleRate = legacySampleRate(head).takeIf { it in 1..MAX_SAMPLE_RATE } ?: return null
                    NarrationClipInfo(sampleRate, frames, compressed = false, codec = null)
                }
            }
        }.getOrNull()

    /** The sample rate a legacy PCM clip's first four bytes hold. */
    fun legacySampleRate(head: ByteArray): Int = ByteBuffer.wrap(head, 0, LEGACY_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).int

    /** The fixed header at the start of [head], checked against a file of [length] bytes; null when invalid. */
    private fun header(
        head: ByteArray,
        length: Long,
    ): Header? {
        if (head.size < FIXED_HEADER_BYTES || !isCompressed(head)) return null
        val buffer = ByteBuffer.wrap(head, 0, FIXED_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(MAGIC.size)
        if (buffer.get().toInt() != VERSION) return null
        val header =
            Header(
                codec = buffer.get().toInt(),
                sampleRate = buffer.int,
                frames = buffer.int,
                delay = buffer.int,
                setupSize =
                    buffer.short.toInt() and 0xFFFF,
            )
        val valid =
            header.sampleRate in 1..MAX_SAMPLE_RATE && header.frames >= 0 && header.delay >= 0 &&
                length >= FIXED_HEADER_BYTES + header.setupSize &&
                // An AAC clip with audio cannot decode without its AudioSpecificConfig.
                (header.codec != CODEC_AAC_LC || header.frames == 0 || header.setupSize > 0)
        return header.takeIf { valid }
    }

    private class Header(
        val codec: Int,
        val sampleRate: Int,
        val frames: Int,
        val delay: Int,
        val setupSize: Int,
    )

    /**
     * The clip's own PCM out of what the decoder produced: [delayFrames] of codec start-up skipped,
     * then exactly [frames], padded with silence if the decoder stopped short.
     */
    fun trim(
        decoded: ByteArray,
        delayFrames: Int,
        frames: Int,
    ): ByteArray {
        val out = ByteArray(frames * BYTES_PER_FRAME)
        val start = delayFrames * BYTES_PER_FRAME
        val available = (decoded.size - start).coerceIn(0, out.size)
        if (available > 0) System.arraycopy(decoded, start, out, 0, available)
        return out
    }
}

/** A compressed clip: what [NarrationClipFormat] stores and a [NarrationClipCodec] produces. */
internal class NarrationEncodedClip(
    val codec: Int,
    val sampleRate: Int,
    /** PCM frames the clip holds once decoded and trimmed. */
    val frames: Int,
    /** Frames of codec start-up the decoder emits before the clip's first frame. */
    val delayFrames: Int,
    /** Codec setup (for AAC, the AudioSpecificConfig). */
    val setup: ByteArray,
    /** Encoded access units in order. */
    val units: List<ByteArray>,
)

internal data class NarrationClipInfo(
    val sampleRate: Int,
    val frames: Long,
    val compressed: Boolean,
    /** The compressed clip's codec ([NarrationClipFormat.CODEC_AAC_LC]); null for legacy PCM. */
    val codec: Int?,
)
