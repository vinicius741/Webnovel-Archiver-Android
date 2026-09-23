package com.vinicius741.webnovelarchiver.data.storage

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** In-process cache revision for mutable local cover files; reading it never stats the file. */
internal object LocalImageRevision {
    private val revisions = ConcurrentHashMap<String, AtomicInteger>()
    private val rootRevision = AtomicInteger()

    fun current(file: File): String = "${rootRevision.get()}:${revisions[file.absolutePath]?.get() ?: 0}"

    fun changed(file: File) {
        revisions.computeIfAbsent(file.absolutePath) { AtomicInteger() }.incrementAndGet()
    }

    /** A full restore can replace every cover while keeping each path unchanged. */
    fun rootReplaced() {
        rootRevision.incrementAndGet()
    }
}
