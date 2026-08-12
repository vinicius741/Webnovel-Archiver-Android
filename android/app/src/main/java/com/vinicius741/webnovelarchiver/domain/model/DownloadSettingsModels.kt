package com.vinicius741.webnovelarchiver.domain.model

data class AppSettings(
    /**
     * Legacy total-worker setting retained for backup compatibility. Download scheduling now uses
     * [maxParallelSources], while requests inside each source lane remain sequential.
     */
    val downloadConcurrency: Int = 1,
    /** Null identifies settings written before source-aware scheduling; normalization migrates it to 2. */
    val maxParallelSources: Int? = null,
    val downloadDelay: Long = 500,
    val downloadDelayMax: Long = 500,
    val maxChaptersPerEpub: Int = 150,
)

data class SourceDownloadSettings(
    /** Legacy field retained so old source overrides and backups keep round-tripping. */
    val concurrency: Int = 1,
    val delay: Long = 500,
    val delayMax: Long = 500,
)
