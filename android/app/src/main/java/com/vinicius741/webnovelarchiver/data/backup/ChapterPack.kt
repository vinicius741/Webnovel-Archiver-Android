package com.vinicius741.webnovelarchiver.data.backup

import com.github.luben.zstd.ZstdInputStreamNoFinalizer
import com.github.luben.zstd.ZstdOutputStreamNoFinalizer
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipException

/**
 * One story's downloaded chapters as a single zstd stream (`chapter_packs/<story>.zst` in a
 * version-3 full backup). Compressing a story's chapters together lets zstd reuse the markup and
 * prose the chapters share, which per-file ZIP deflate cannot (docs/architecture/full-backup-chapter-packs.md).
 *
 * The decompressed stream is: the 8-byte [MAGIC], then one record per chapter file
 * (`u16` name length, UTF-8 file name, `u32` content length, content; big-endian), then a `u16` 0
 * terminator, then end of stream. Names are bare file names inside the story's `novels/<story>/`
 * directory, so a pack can only ever produce chapter files of its own story.
 */
object ChapterPack {
    /**
     * Compression level, chosen for phone CPU time. On the emulator's 24,468-chapter library, level 6
     * exported in 29 s against 37 s for the former per-file deflate and 40 s for level 9, and its
     * packs were only 3.5% larger than level 9's (docs/architecture/full-backup-chapter-packs.md).
     */
    const val ZSTD_LEVEL = 6

    /**
     * Largest zstd window a pack may need, as a power of two. Levels up to 19 use at most 8 MB
     * (2^23); the cap bounds the decoder's memory against a hostile frame header.
     */
    const val MAX_WINDOW_LOG = 24

    const val MAX_NAME_BYTES = 1024

    private val MAGIC =
        byteArrayOf(
            'W'.code.toByte(),
            'N'.code.toByte(),
            'A'.code.toByte(),
            'P'.code.toByte(),
            'A'.code.toByte(),
            'C'.code.toByte(),
            'K'.code.toByte(),
            1,
        )
    private const val MAX_U32 = 0xFFFF_FFFFL
    private const val BUFFER_BYTES = 64 * 1024

    /** Writes one pack into [output], which stays open (it is the ZIP stream). */
    class Writer(
        output: OutputStream,
        level: Int = ZSTD_LEVEL,
    ) : Closeable {
        private val zstd = ZstdOutputStreamNoFinalizer(NonClosingOutputStream(output), level).setChecksum(true)
        private val data = DataOutputStream(BufferedOutputStream(zstd, BUFFER_BYTES))
        private var closed = false

        init {
            data.write(MAGIC)
        }

        fun add(
            name: String,
            content: ByteArray,
        ) {
            check(!closed) { "Chapter pack already finished" }
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            require(isValidName(name) && nameBytes.size <= MAX_NAME_BYTES) { "Invalid chapter pack file name: $name" }
            require(content.size.toLong() <= MAX_U32) { "Chapter file too large for a pack: $name" }
            data.writeShort(nameBytes.size)
            data.write(nameBytes)
            data.writeInt(content.size)
            data.write(content)
        }

        /** Writes the terminator and ends the zstd frame; the underlying stream stays open. */
        override fun close() {
            if (closed) return
            closed = true
            data.writeShort(0)
            data.flush()
            zstd.close()
        }
    }

    /**
     * Reads a pack from [input] (left open), calling [onFile] for each chapter file with its name,
     * declared size, and a stream of exactly that many bytes. Sizes above [maxFileBytes] are rejected
     * before any content is read. Malformed, truncated or trailing data throws [ZipException], so
     * restore reports the backup as corrupt.
     */
    fun read(
        input: InputStream,
        maxFileBytes: Long,
        onFile: (name: String, size: Long, content: InputStream) -> Unit,
    ) {
        val zstd =
            try {
                ZstdInputStreamNoFinalizer(NonClosingInputStream(input)).setLongMax(MAX_WINDOW_LOG)
            } catch (error: IOException) {
                throw corrupt("${error.message}", error)
            }
        zstd.use { decoded ->
            val data = DataInputStream(BufferedInputStream(CorruptionAsZipException(decoded), BUFFER_BYTES))
            corruptOnEof {
                val magic = ByteArray(MAGIC.size).also(data::readFully)
                if (!magic.contentEquals(MAGIC)) throw ZipException("Corrupt chapter pack: bad header")
                while (true) {
                    val nameLength = data.readUnsignedShort()
                    if (nameLength == 0) break
                    if (nameLength > MAX_NAME_BYTES) throw ZipException("Corrupt chapter pack: file name too long")
                    val name = decodeName(ByteArray(nameLength).also(data::readFully))
                    if (!isValidName(name)) throw ZipException("Corrupt chapter pack: invalid file name")
                    val size = data.readInt().toLong() and MAX_U32
                    check(size <= maxFileBytes) { "Backup entry too large: $name" }
                    val content = ExactLengthInputStream(data, size)
                    onFile(name, size, content)
                    content.skipRemaining()
                }
                if (data.read() >= 0) throw ZipException("Corrupt chapter pack: trailing data")
            }
        }
    }

    /** A bare file name: no directory separators, no path tricks, no control characters. */
    private fun isValidName(name: String): Boolean =
        name.isNotEmpty() &&
            name != "." &&
            name != ".." &&
            name.none { it == '/' || it == '\\' || it.isISOControl() }

    private fun decodeName(bytes: ByteArray): String =
        try {
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (error: CharacterCodingException) {
            throw corrupt("file name is not UTF-8", error)
        }

    private inline fun corruptOnEof(block: () -> Unit) {
        try {
            block()
        } catch (error: EOFException) {
            throw corrupt("truncated", error)
        }
    }

    private fun corrupt(
        detail: String,
        cause: Throwable,
    ): ZipException = ZipException("Corrupt chapter pack: $detail").apply { initCause(cause) }

    /** zstd reports a damaged or truncated frame as a plain IOException; restore shows ZipExceptions as a corrupt backup. */
    private class CorruptionAsZipException(
        input: InputStream,
    ) : FilterInputStream(input) {
        override fun read(): Int = guarded { super.read() }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int = guarded { super.read(b, off, len) }

        private inline fun guarded(block: () -> Int): Int =
            try {
                block()
            } catch (error: ZipException) {
                throw error
            } catch (error: IOException) {
                throw corrupt("${error.message}", error)
            }
    }

    /** Exactly [length] bytes of [input]; ending early is a truncated pack. */
    private class ExactLengthInputStream(
        private val input: InputStream,
        length: Long,
    ) : InputStream() {
        private var remaining = length

        override fun read(): Int {
            if (remaining == 0L) return -1
            val value = input.read()
            if (value < 0) throw ZipException("Corrupt chapter pack: truncated")
            remaining -= 1
            return value
        }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            if (len == 0) return 0
            if (remaining == 0L) return -1
            val count = input.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (count < 0) throw ZipException("Corrupt chapter pack: truncated")
            remaining -= count
            return count
        }

        fun skipRemaining() {
            val buffer = ByteArray(BUFFER_BYTES)
            do {
                val count = read(buffer, 0, buffer.size)
            } while (count >= 0)
        }
    }

    private class NonClosingOutputStream(
        output: OutputStream,
    ) : FilterOutputStream(output) {
        override fun write(
            b: ByteArray,
            off: Int,
            len: Int,
        ) = out.write(b, off, len)

        override fun close() = flush()
    }

    private class NonClosingInputStream(
        input: InputStream,
    ) : FilterInputStream(input) {
        override fun close() = Unit
    }
}
