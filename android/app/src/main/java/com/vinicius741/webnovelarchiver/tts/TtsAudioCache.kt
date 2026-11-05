package com.vinicius741.webnovelarchiver.tts

import timber.log.Timber
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Rendered sentences on disk, addressed by voice + text so renumbering never invalidates them and
 * repeated lines render once. It lives in the app cache: the OS may reclaim it and backups skip it.
 * Least recently used files go first once [maxBytes] is exceeded.
 */
internal class TtsAudioCache(
    private val dir: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    private val lock = Object()
    private var writesSinceTrim = 0

    fun keyFor(
        voiceKey: String,
        text: String,
    ): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest("$FORMAT_VERSION|$voiceKey|$text".toByteArray())
            .joinToString("") { "%02x".format(it) }

    fun contains(key: String): Boolean = file(key).isFile

    /** Reads the length from the header alone, so a whole chapter's timing stays cheap. */
    fun durationMs(key: String): Long? {
        val target = file(key)
        if (!target.isFile) return null
        val sampleRate =
            runCatching {
                target.inputStream().use { input ->
                    val header = ByteArray(HEADER_BYTES)
                    if (input.read(header) == HEADER_BYTES) ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).int else 0
                }
            }.getOrDefault(0)
        return TtsPcmPlanning.framesToMs((target.length() - HEADER_BYTES) / BYTES_PER_FRAME, sampleRate)
    }

    fun read(key: String): TtsPcmClip? {
        val target = file(key)
        if (!target.isFile) return null
        return runCatching {
            val bytes = target.readBytes()
            target.setLastModified(System.currentTimeMillis())
            val sampleRate = ByteBuffer.wrap(bytes, 0, HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).int
            TtsPcmClip(sampleRate, bytes.copyOfRange(HEADER_BYTES, bytes.size))
        }.onFailure { Timber.w(it, "TTS audio cache read failed") }.getOrNull()
    }

    fun write(
        key: String,
        clip: TtsPcmClip,
    ) {
        dir.mkdirs()
        val temporary = File(dir, "$key.tmp")
        val header =
            ByteBuffer
                .allocate(HEADER_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(clip.sampleRate)
                .array()
        temporary.outputStream().use { output ->
            output.write(header)
            output.write(clip.pcm)
        }
        if (!temporary.renameTo(file(key))) temporary.delete()
        synchronized(lock) {
            lock.notifyAll()
            writesSinceTrim++
            if (writesSinceTrim < WRITES_BETWEEN_TRIMS) return
            writesSinceTrim = 0
        }
        trim()
    }

    /** Waits up to [timeoutMs] for another thread's [write]; null means not rendered yet. */
    fun awaitClip(
        key: String,
        timeoutMs: Long,
    ): TtsPcmClip? {
        synchronized(lock) {
            if (!contains(key)) lock.wait(timeoutMs)
        }
        return read(key)
    }

    fun temporaryFile(name: String): File {
        dir.mkdirs()
        return File(dir, "$name.wav.tmp")
    }

    private fun trim() {
        val files = dir.listFiles { candidate -> candidate.extension == EXTENSION }.orEmpty()
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        files.sortedBy { it.lastModified() }.forEach { oldest ->
            if (total <= maxBytes) return
            val length = oldest.length()
            if (oldest.delete()) total -= length
        }
    }

    private fun file(key: String): File = File(dir, "$key.$EXTENSION")

    private companion object {
        const val FORMAT_VERSION = 1
        const val HEADER_BYTES = 4
        const val EXTENSION = "pcm"
        const val WRITES_BETWEEN_TRIMS = 64

        /** About three hours of speech at 24 kHz. */
        const val DEFAULT_MAX_BYTES = 500L * 1024L * 1024L
    }
}
