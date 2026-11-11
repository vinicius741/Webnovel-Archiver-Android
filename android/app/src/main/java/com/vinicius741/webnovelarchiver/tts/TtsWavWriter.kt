package com.vinicius741.webnovelarchiver.tts

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Writes float samples as the mono 16-bit PCM WAV every [TtsSpeechSynthesizer] produces. */
internal object TtsWavWriter {
    private const val HEADER_BYTES = 44
    private const val FMT_CHUNK_BYTES = 16
    private const val PCM_FORMAT: Short = 1
    private const val CHANNELS: Short = 1
    private const val BITS: Short = 16
    private const val BYTES_PER_SAMPLE = 2
    private const val RIFF_SIZE_OFFSET = 36

    fun mono16(
        samples: FloatArray,
        sampleRate: Int,
    ): ByteArray {
        val dataBytes = samples.size * BYTES_PER_SAMPLE
        val buffer = ByteBuffer.allocate(HEADER_BYTES + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(RIFF_SIZE_OFFSET + dataBytes)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(FMT_CHUNK_BYTES)
        buffer.putShort(PCM_FORMAT)
        buffer.putShort(CHANNELS)
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * BYTES_PER_SAMPLE)
        buffer.putShort((BYTES_PER_SAMPLE).toShort())
        buffer.putShort(BITS)
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataBytes)
        samples.forEach { sample ->
            buffer.putShort((sample.coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt().toShort())
        }
        return buffer.array()
    }
}
