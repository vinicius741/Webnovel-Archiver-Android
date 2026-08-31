package com.vinicius741.webnovelarchiver.data.storage

import android.system.Os
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Write-then-rename helpers for large binary/streamed files (chapter HTML, EPUBs). Each write
 * lands in a sibling temp file, is fsync'd, then atomically renamed over its destination — a
 * crash can never leave a truncated file there. The parent dir is fsync'd after the rename;
 * temp files are cleaned up on failure.
 */
object AtomicFileWrites {
    /** Monotonic counter so concurrent temp files never collide. */
    private val tempCounter = AtomicInteger()

    fun writeBytes(
        destination: File,
        bytes: ByteArray,
    ): File = writeAtomically(destination) { it.write(bytes) }.let { destination }

    fun writeText(
        destination: File,
        text: String,
    ): File = writeAtomically(destination) { it.write(text.toByteArray(Charsets.UTF_8)) }.let { destination }

    /** Streams into a temp file, then renames it onto [destination] when [block] returns normally. */
    fun <R> writeAtomically(
        destination: File,
        block: (OutputStream) -> R,
    ): R {
        destination.parentFile?.mkdirs()
        val temp = tempSibling(destination)
        val result: R
        try {
            result =
                FileOutputStream(temp).use { out ->
                    val value = block(out)
                    out.flush()
                    value
                }
            fsync(temp)
            renameOnto(temp, destination)
        } finally {
            cleanupTempIfPresent(temp)
        }
        return result
    }

    private fun tempSibling(destination: File): File {
        val parent = destination.parentFile ?: error("Destination has no parent: $destination")
        val n = tempCounter.incrementAndGet()
        return File(parent, "${destination.name}.tmp.$n")
    }

    /**
     * Renames [temp] over [destination] — a same-filesystem POSIX rename (temp is a sibling)
     * atomically replaces it, so the destination is never missing. Falls back to copy-then-delete
     * only if renameTo returns false (e.g. cross-device).
     */
    private fun renameOnto(
        temp: File,
        destination: File,
    ) {
        if (!temp.renameTo(destination)) {
            temp.copyTo(destination, overwrite = true)
            temp.delete()
        }
        // fsync the parent directory so the rename/replacement survives a crash after it returns.
        fsyncDir(destination.parentFile)
    }

    /** Ensure no orphaned `.tmp.N` is left behind when a write or rename fails. */
    private fun cleanupTempIfPresent(temp: File) {
        if (temp.exists()) {
            // If the temp still exists here, the write or rename failed.
            runCatching { temp.delete() }
        }
    }

    private fun fsync(file: File) {
        // Open read-only (no truncation) and force data+metadata to disk before the rename.
        runCatching {
            java.io.RandomAccessFile(file, "r").use { raf -> raf.fd.sync() }
        }
    }

    /**
     * fsync's the directory so the rename (a directory entry update) is durable across power loss.
     * [Os] is the only way to fsync a directory from the JVM. Catches [Throwable] (not just
     * [Exception]) because `android.system.Os` is absent on the pure-JVM unit-test classpath
     * (throws NoClassDefFoundError) — best-effort, must never abort the write.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun fsyncDir(dir: File?) {
        if (dir == null) return
        val fd =
            try {
                Os.open(dir.absolutePath, android.system.OsConstants.O_RDONLY, 0)
            } catch (error: Throwable) {
                return
            }
        try {
            Os.fsync(fd)
        } catch (error: Throwable) {
            // The write itself is already durable via the temp-file fsync.
        } finally {
            runCatching { Os.close(fd) }
        }
    }
}
