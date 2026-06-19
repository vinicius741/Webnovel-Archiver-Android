package com.vinicius741.webnovelarchiver.feature.settings

object SettingsValidation {
    const val CONCURRENCY_MIN = 1
    const val CONCURRENCY_MAX = 10
    const val DELAY_MIN = 0L
    const val MAX_CHAPTERS_PER_EPUB_MIN = 10
    const val MAX_CHAPTERS_PER_EPUB_MAX = 1000
    const val TTS_MIN = 0.5f
    const val TTS_MAX = 2.0f
    const val TTS_CHUNK_SIZE_MIN = 100

    fun concurrency(
        value: String,
        fallback: Int = CONCURRENCY_MIN,
    ): Int = (value.toIntOrNull() ?: fallback).coerceIn(CONCURRENCY_MIN, CONCURRENCY_MAX)

    fun delay(
        value: String,
        fallback: Long = 500L,
    ): Long = (value.toLongOrNull() ?: fallback).coerceAtLeast(DELAY_MIN)

    fun maxChaptersPerEpub(
        value: String,
        fallback: Int = 150,
    ): Int = (value.toIntOrNull() ?: fallback).coerceIn(MAX_CHAPTERS_PER_EPUB_MIN, MAX_CHAPTERS_PER_EPUB_MAX)

    fun ttsScalar(
        value: String,
        fallback: Float = 1.0f,
    ): Float = (value.toFloatOrNull() ?: fallback).coerceIn(TTS_MIN, TTS_MAX)

    fun ttsChunkSize(
        value: String,
        fallback: Int = 500,
    ): Int = (value.toIntOrNull() ?: fallback).coerceAtLeast(TTS_CHUNK_SIZE_MIN)
}
