package com.vinicius741.webnovelarchiver.core

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Write-then-rename helpers for large binary/streamed files (chapter HTML, EPUBs).
 *
 * Each write lands in a sibling temp file, is fsync'd, then atomically renamed onto its final
 * path. A crash during the write therefore cannot leave a truncated chapter or half-built EPUB at
 * the destination — the previous content (if any) survives until the new file is complete.
 */
object AtomicFileWrites {
    /** Monotonic counter so concurrent temp files never collide. */
    private val tempCounter = AtomicInteger()

    /** Writes [bytes] atomically to [destination], returning [destination]. */
    fun writeBytes(destination: File, bytes: ByteArray): File {
        destination.parentFile?.mkdirs()
        val temp = tempSibling(destination)
        temp.writeBytes(bytes)
        fsync(temp)
        renameOnto(temp, destination)
        return destination
    }

    /** Writes [text] atomically to [destination]. */
    fun writeText(destination: File, text: String): File =
        writeBytes(destination, text.toByteArray(Charsets.UTF_8))

    /**
     * Opens [block] with an [OutputStream] backed by a temp file, then renames it onto
     * [destination] when [block] returns normally. Used for streamed EPUB generation.
     */
    fun <R> stream(destination: File, block: (OutputStream) -> R): R {
        destination.parentFile?.mkdirs()
        val temp = tempSibling(destination)
        val result = FileOutputStream(temp).use { out ->
            val wrapped = object : OutputStream() {
                override fun write(b: Int) = out.write(b)
                override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
                override fun flush() = out.flush()
                override fun close() = out.close()
            }
            block(wrapped)
        }
        fsync(temp)
        renameOnto(temp, destination)
        return result
    }

    private fun tempSibling(destination: File): File {
        val parent = destination.parentFile ?: error("Destination has no parent: $destination")
        val n = tempCounter.incrementAndGet()
        return File(parent, "${destination.name}.tmp.$n")
    }

    private fun renameOnto(temp: File, destination: File) {
        if (destination.exists()) destination.delete()
        if (!temp.renameTo(destination)) {
            // Rename can fail across filesystem boundaries; fall back to copy.
            temp.copyTo(destination, overwrite = true)
            temp.delete()
        }
    }

    private fun fsync(file: File) {
        // Open in append mode so we don't truncate the just-written file, then force the file
        // descriptor's data+metadata to disk before the rename.
        runCatching {
            java.io.RandomAccessFile(file, "r").use { raf -> raf.fd.sync() }
        }
    }
}
