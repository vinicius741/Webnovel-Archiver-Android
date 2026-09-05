package com.vinicius741.webnovelarchiver.data.storage

import android.system.Os
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * File-operation seam so tests can inject rename, copy, and fsync failures (R06). Production code
 * uses [DEFAULT]; the helpers below route every mutation through it.
 */
interface AtomicFileOps {
    /** Returns false when the atomic rename cannot complete; the caller must then fail the write. */
    fun rename(
        temp: File,
        destination: File,
    ): Boolean

    /** Throws [IOException] when file data cannot be made durable. */
    fun fsync(file: File)

    /** Best-effort directory fsync; never fatal. */
    fun fsyncDir(dir: File)

    /** Streams [source] into [destination]; throws on failure without truncating it mid-way. */
    fun copy(
        source: File,
        destination: File,
    )
}

object DefaultAtomicFileOps : AtomicFileOps {
    override fun rename(
        temp: File,
        destination: File,
    ): Boolean = temp.renameTo(destination)

    override fun fsync(file: File) {
        // Open read-only (no truncation) and force data+metadata to disk before the rename.
        java.io.RandomAccessFile(file, "r").use { raf -> raf.fd.sync() }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    override fun fsyncDir(dir: File) {
        // [Os] is the only way to fsync a directory from the JVM. Catches [Throwable] (not just
        // [Exception]) because `android.system.Os` is absent on the pure-JVM unit-test classpath
        // (throws NoClassDefFoundError) — best-effort, must never abort the write.
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

    override fun copy(
        source: File,
        destination: File,
    ) {
        source.inputStream().use { input -> FileOutputStream(destination).use { output -> input.copyTo(output) } }
    }
}

/**
 * Write-then-rename helpers for large binary/streamed files (chapter HTML, EPUBs). Each write
 * lands in a sibling temp file, is fsync'd, then atomically renamed over its destination — a
 * crash can never leave a truncated file there. The parent dir is fsync'd after the rename;
 * temp files are cleaned up on failure.
 *
 * If the rename or the pre-rename fsync cannot complete, the operation fails and the previous
 * destination is left fully intact (R06): a fallback copy-over would risk truncating the known
 * good file halfway through.
 */
object AtomicFileWrites {
    /** Monotonic counter so concurrent temp files never collide. */
    private val tempCounter = AtomicInteger()

    @Volatile
    internal var ops: AtomicFileOps = DefaultAtomicFileOps

    /** Replaces the file-operation seam; tests only. Restore with [useDefaultOps]. */
    internal fun useDefaultOps() {
        ops = DefaultAtomicFileOps
    }

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
            ops.fsync(temp)
            renameOnto(temp, destination)
        } finally {
            cleanupTempIfPresent(temp)
        }
        return result
    }

    /**
     * Copies [source] onto [destination] through the same temp + fsync + rename commit, so a
     * failed copy can never leave a half-written destination (R06; used by archive chapter copies).
     */
    fun copyAtomically(
        source: File,
        destination: File,
    ) {
        destination.parentFile?.mkdirs()
        val temp = tempSibling(destination)
        try {
            ops.copy(source, temp)
            ops.fsync(temp)
            renameOnto(temp, destination)
        } finally {
            cleanupTempIfPresent(temp)
        }
    }

    private fun tempSibling(destination: File): File {
        val parent = destination.parentFile ?: error("Destination has no parent: $destination")
        val n = tempCounter.incrementAndGet()
        return File(parent, "${destination.name}.tmp.$n")
    }

    /**
     * Renames [temp] over [destination] — a same-filesystem POSIX rename (temp is a sibling)
     * atomically replaces it, so the destination is never missing. A false return is a real
     * filesystem failure (the temp is a sibling, so EXDEV cannot apply): fail the whole write,
     * leaving the previous destination intact, rather than copying over it non-atomically (R06).
     */
    private fun renameOnto(
        temp: File,
        destination: File,
    ) {
        check(ops.rename(temp, destination)) {
            "Atomic rename of ${destination.name} failed; previous file left untouched"
        }
        // fsync the parent directory so the rename/replacement survives a crash after it returns.
        ops.fsyncDir(destination.parentFile)
    }

    /** Ensure no orphaned `.tmp.N` is left behind when a write or rename fails. */
    private fun cleanupTempIfPresent(temp: File) {
        if (temp.exists()) {
            // If the temp still exists here, the write or rename failed.
            runCatching { temp.delete() }
        }
    }
}
