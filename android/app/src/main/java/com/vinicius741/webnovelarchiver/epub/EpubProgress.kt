package com.vinicius741.webnovelarchiver.epub

/** Progress for one EPUB volume in a multi-volume export. */
data class EpubProgress(
    val completed: Int,
    val total: Int,
) {
    init {
        require(total > 0) { "EPUB progress total must be positive" }
        require(completed in 1..total) { "EPUB progress must be within its total" }
    }
}
