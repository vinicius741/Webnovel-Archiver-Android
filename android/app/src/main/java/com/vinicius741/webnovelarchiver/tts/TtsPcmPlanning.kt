package com.vinicius741.webnovelarchiver.tts

import kotlin.math.abs

/** 16-bit little-endian mono audio. */
internal class TtsPcmClip(
    val sampleRate: Int,
    val pcm: ByteArray,
) {
    val frames: Int get() = pcm.size / BYTES_PER_FRAME
    val durationMs: Long get() = TtsPcmPlanning.framesToMs(frames.toLong(), sampleRate)
}

internal const val BYTES_PER_FRAME = 2

/** Pure WAV parsing and silence trimming for what the speech engine renders. */
internal object TtsPcmPlanning {
    private const val WAV_HEADER_MIN_BYTES = 12
    private const val CHUNK_HEADER_BYTES = 8
    private const val MS_PER_SECOND = 1_000L
    private const val PCM_BITS = 16

    /** About -50 dBFS: below this a voice's lead-in and tail read as silence. */
    private const val SILENCE_AMPLITUDE = 103
    private const val KEEP_PADDING_MS = 15L
    private const val FADE_MS = 5L

    /**
     * Shorter silences are mostly the closure before a stop consonant, which must not stretch. In a
     * Google voice at 1x, gaps at commas measured 80 to 160 ms, with few between 60 and 80.
     */
    private const val PHRASE_GAP_MIN_MS = 80L

    fun framesToMs(
        frames: Long,
        sampleRate: Int,
    ): Long = if (sampleRate <= 0) 0L else frames * MS_PER_SECOND / sampleRate

    fun msToFrames(
        ms: Long,
        sampleRate: Int,
    ): Long = ms * sampleRate / MS_PER_SECOND

    /** The mono 16-bit audio in a RIFF/WAVE file, or null for anything else. */
    fun parseWav(bytes: ByteArray): TtsPcmClip? {
        if (bytes.size < WAV_HEADER_MIN_BYTES || tag(bytes, 0) != "RIFF" || tag(bytes, 8) != "WAVE") return null
        var sampleRate = 0
        var channels = 0
        var bits = 0
        var offset = WAV_HEADER_MIN_BYTES
        while (offset + CHUNK_HEADER_BYTES <= bytes.size) {
            val name = tag(bytes, offset)
            val size = int32(bytes, offset + 4)
            val body = offset + CHUNK_HEADER_BYTES
            if (name == "fmt " && body + 16 <= bytes.size) {
                channels = int16(bytes, body + 2)
                sampleRate = int32(bytes, body + 4)
                bits = int16(bytes, body + 14)
            } else if (name == "data") {
                if (channels != 1 || bits != PCM_BITS || sampleRate <= 0) return null
                // An engine still streaming can leave a zero or oversized length in the header.
                val available = bytes.size - body
                val length = if (size in 1..available) size else available
                return TtsPcmClip(sampleRate, bytes.copyOfRange(body, body + length - length % BYTES_PER_FRAME))
            }
            if (size < 0) return null
            offset = body + size + size % 2
        }
        return null
    }

    /**
     * Cuts the engine's lead-in and tail, which vary from sentence to sentence, so the pauses heard
     * are the ones the narration plan chose. A short fade keeps the cuts from clicking.
     */
    fun trimSilence(clip: TtsPcmClip): TtsPcmClip {
        val frames = clip.frames
        var first = 0
        while (first < frames && abs(sample(clip.pcm, first)) < SILENCE_AMPLITUDE) first++
        if (first == frames) return TtsPcmClip(clip.sampleRate, ByteArray(0))
        var last = frames - 1
        while (last > first && abs(sample(clip.pcm, last)) < SILENCE_AMPLITUDE) last--
        val padding = msToFrames(KEEP_PADDING_MS, clip.sampleRate).toInt()
        val start = (first - padding).coerceAtLeast(0)
        val end = (last + 1 + padding).coerceAtMost(frames)
        val trimmed = clip.pcm.copyOfRange(start * BYTES_PER_FRAME, end * BYTES_PER_FRAME)
        applyFades(trimmed, msToFrames(FADE_MS, clip.sampleRate).toInt())
        return TtsPcmClip(clip.sampleRate, trimmed)
    }

    /** The middle of each silent gap inside the speech: where a phrase pause can go unnoticed. */
    fun phraseBreaks(clip: TtsPcmClip): List<Int> {
        val minFrames = msToFrames(PHRASE_GAP_MIN_MS, clip.sampleRate)
        val breaks = mutableListOf<Int>()
        var gapStart = -1
        var heardSpeech = false
        for (frame in 0 until clip.frames) {
            if (abs(sample(clip.pcm, frame)) < SILENCE_AMPLITUDE) {
                if (gapStart < 0) gapStart = frame
            } else {
                if (heardSpeech && gapStart >= 0 && frame - gapStart >= minFrames) breaks += (gapStart + frame) / 2
                gapStart = -1
                heardSpeech = true
            }
        }
        return breaks
    }

    private fun applyFades(
        pcm: ByteArray,
        fadeFrames: Int,
    ) {
        fun scaleSample(
            frame: Int,
            scale: Float,
        ) {
            val scaled = (sample(pcm, frame) * scale).toInt()
            pcm[frame * BYTES_PER_FRAME] = scaled.toByte()
            pcm[frame * BYTES_PER_FRAME + 1] = (scaled shr 8).toByte()
        }
        val frames = pcm.size / BYTES_PER_FRAME
        val length = fadeFrames.coerceAtMost(frames / 2)
        for (index in 0 until length) {
            scaleSample(index, index.toFloat() / length)
            scaleSample(frames - 1 - index, index.toFloat() / length)
        }
    }

    private fun sample(
        pcm: ByteArray,
        frame: Int,
    ): Int = (pcm[frame * BYTES_PER_FRAME + 1].toInt() shl 8) or (pcm[frame * BYTES_PER_FRAME].toInt() and 0xFF)

    private fun tag(
        bytes: ByteArray,
        offset: Int,
    ): String = String(bytes, offset, 4, Charsets.US_ASCII)

    private fun int16(
        bytes: ByteArray,
        offset: Int,
    ): Int = (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun int32(
        bytes: ByteArray,
        offset: Int,
    ): Int = int16(bytes, offset) or (int16(bytes, offset + 2) shl 16)
}
