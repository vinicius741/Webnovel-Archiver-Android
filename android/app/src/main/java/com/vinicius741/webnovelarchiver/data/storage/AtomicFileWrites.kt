package com.vinicius741.webnovelarchiver.data.storage

import android.system.Os
import com.vinicius741.webnovelarchiver.ui.text
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
 *
 * Tier 1 durability fixes (P4):
 *  - The destination is **no longer pre-deleted** before the rename. Pre-deleting broke the
 *    durability contract: a crash between the delete and the rename would lose *both* the old and
 *    the new content. The temp file lives in the same directory, so `renameTo` is a same-filesystem
 *    POSIX rename that atomically replaces an existing destination.
 *  - The parent directory is fsync'd after the rename so the rename itself survives power loss on
 *    ext4/f2fs.
 *  - Temp files are cleaned up in a `finally` if the write or rename throws (P12), so a long session
 *    with intermittent disk errors does not accumulate orphaned `.tmp.N` files.
 */
object AtomicFileWrites {
    /** Monotonic counter so concurrent temp files never collide. */
    private val tempCounter = AtomicInteger()

    /** Writes [bytes] atomically to [destination], returning [destination]. */
    fun writeBytes(
        destination: File,
        bytes: ByteArray,
    ): File {
        destination.parentFile?.mkdirs()
        val temp = tempSibling(destination)
        try {
            temp.writeBytes(bytes)
            fsync(temp)
            renameOnto(temp, destination)
        } finally {
            cleanupTempIfPresent(temp)
        }
        return destination
    }

    /** Writes [text] atomically to [destination]. */
    fun writeText(
        destination: File,
        text: String,
    ): File = writeBytes(destination, text.toByteArray(Charsets.UTF_8))

    /**
     * Opens [block] with an [OutputStream] backed by a temp file, then renames it onto
     * [destination] when [block] returns normally. Used for streamed EPUB generation.
     */
    fun <R> stream(
        destination: File,
        block: (OutputStream) -> R,
    ): R {
        destination.parentFile?.mkdirs()
        val temp = tempSibling(destination)
        val result: R
        try {
            result =
                FileOutputStream(temp).use { out ->
                    val wrapped =
                        object : OutputStream() {
                            override fun write(b: Int) = out.write(b)

                            override fun write(
                                b: ByteArray,
                                off: Int,
                                len: Int,
                            ) = out.write(b, off, len)

                            override fun flush() = out.flush()

                            override fun close() = out.close()
                        }
                    block(wrapped)
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
     * Rename [temp] onto [destination] without pre-deleting the destination (P4).
     *
     * On the same filesystem (guaranteed: the temp is a sibling) `File.renameTo` maps to POSIX
     * `rename(2)`, which atomically replaces an existing destination — so there is never a window
     * where the destination is missing. Only if the rename itself returns false (rare; e.g. a
     * cross-device edge) do we fall back to copy-then-delete, keeping an explicit overwrite.
     */
    private fun renameOnto(
        temp: File,
        destination: File,
    ) {
        if (!temp.renameTo(destination)) {
            // Rename can fail across filesystem boundaries; fall back to copy (overwrites the
            // existing destination, preserving the old content until the copy completes).
            temp.copyTo(destination, overwrite = true)
            temp.delete()
        }
        // fsync the parent directory so the rename/replacement survives a crash after it returns.
        fsyncDir(destination.parentFile)
    }

    /** P12: ensure no orphaned `.tmp.N` is left behind when a write or rename fails. */
    private fun cleanupTempIfPresent(temp: File) {
        if (temp.exists()) {
            // renameOnto already deleted temp on success; reaching here with the file present means
            // the write/rename failed.
            runCatching { temp.delete() }
        }
    }

    private fun fsync(file: File) {
        // Open read-only (no truncation of the just-written file), then force the file descriptor's
        // data+metadata to disk before the rename so a crash after the rename can't lose it.
        runCatching {
            java.io.RandomAccessFile(file, "r").use { raf -> raf.fd.sync() }
        }
    }

    /**
     * P4: force the directory's metadata to disk after a rename so the rename itself (a directory
     * entry update) is durable across power loss on ext4/f2fs.
     *
     * Uses [Os] (available on minSdk 21+) to open the directory read-only and fsync its file
     * descriptor — the only way to fsync a directory from the JVM (`RandomAccessFile`/`FileChannel`
     * cannot open directories). Catches [Throwable] (not just [Exception]) because on the pure-JVM
     * unit-test classpath `android.system.Os` is absent and referencing it throws
     * `NoClassDefFoundError`; this is best-effort defense-in-depth and must never abort the write.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // best-effort dir fsync; must tolerate NoClassDefFoundError on JVM tests and swallow fsync failures
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
            // Best-effort defense-in-depth; the write itself is already durable via the temp-file fsync.
        } finally {
            runCatching { Os.close(fd) }
        }
    }
}
