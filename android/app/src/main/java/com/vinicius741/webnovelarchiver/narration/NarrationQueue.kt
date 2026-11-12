package com.vinicius741.webnovelarchiver.narration

import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import java.io.File

/**
 * The generation queue and its settings, persisted to `queue.json` beside the packs. Every change
 * is a read-modify-write under one lock and is on disk before [state] announces it.
 */
class NarrationQueue(
    private val file: File,
) {
    private val gson = Gson()
    private val lock = Any()
    private val _state = MutableStateFlow(NarrationQueueDocument())
    val state: StateFlow<NarrationQueueDocument> get() = _state

    /** Reads the saved queue. Call once, off the main thread. */
    fun load() {
        val document =
            if (file.isFile) {
                runCatching { gson.fromJson(NarrationLegacyJson.queue(file.readText()), NarrationQueueDocument::class.java) }
                    .onFailure { Timber.e(it, "Narration queue unreadable; starting empty") }
                    .getOrNull()
            } else {
                null
            }
        synchronized(lock) { _state.value = sanitize(document ?: NarrationQueueDocument()) }
    }

    fun update(transform: (NarrationQueueDocument) -> NarrationQueueDocument): NarrationQueueDocument =
        synchronized(lock) {
            val next = transform(_state.value)
            if (next != _state.value) {
                save(next)
                _state.value = next
            }
            next
        }

    private fun save(document: NarrationQueueDocument) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.writeText(gson.toJson(document))
        if (!temporary.renameTo(file)) {
            temporary.delete()
            throw java.io.IOException("Could not save the narration queue")
        }
    }

    /** Gson leaves fields absent from older files null even when Kotlin says otherwise. */
    @Suppress("SENSELESS_COMPARISON", "USELESS_ELVIS")
    private fun sanitize(document: NarrationQueueDocument): NarrationQueueDocument =
        document.copy(
            jobs =
                (document.jobs ?: emptyList()).filter { it != null && it.storyId != null && it.chapterId != null && it.voice != null }.map {
                    it.copy(
                        origin = it.origin ?: NarrationOrigin.MANUAL,
                        state = it.state ?: NarrationJobState.QUEUED,
                    )
                },
            msPerChar = document.msPerChar ?: emptyMap(),
            speed = document.speed ?: emptyMap(),
        )
}
