package com.vinicius741.webnovelarchiver.data.storage

import java.io.File

/**
 * Owns the per-story generated-cover files under `covers/` (one current file per story, named by
 * safe story id + media extension). Split out of [AppStorage] to keep that class inside the
 * file-size budget; writes go through [AtomicFileWrites] like every other binary artifact.
 */
internal class CoverFileStore(
    root: File,
    private val safeName: (String) -> String,
) {
    private val dir = File(root, "covers").apply { mkdirs() }

    /** Recreates the directory after a full-backup restore swapped the storage root. */
    fun ensureDirectory() {
        dir.mkdirs()
    }

    /**
     * Atomically writes [storyId]'s cover and returns the file. A cover previously saved under a
     * different extension is removed so at most one cover file per story is ever current. The
     * caller records the returned path (relativized against the storage root) on the story.
     */
    @Synchronized
    fun save(
        storyId: String,
        bytes: ByteArray,
        extension: String,
    ): File {
        val file = File(dir, "${safeName(storyId)}.$extension")
        find(storyId)?.takeIf { it != file }?.delete()
        AtomicFileWrites.writeBytes(file, bytes)
        return file
    }

    /** The story's stored cover file, whatever extension it was saved with; null when there is none. */
    @Synchronized
    fun find(storyId: String): File? = dir.listFiles()?.firstOrNull { it.isFile && it.nameWithoutExtension == safeName(storyId) }

    /** Removes the story's cover file; a no-op if there is none. */
    @Synchronized
    fun delete(storyId: String) {
        find(storyId)?.delete()
    }
}
