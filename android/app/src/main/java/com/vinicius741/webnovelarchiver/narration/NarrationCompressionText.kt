package com.vinicius741.webnovelarchiver.narration

import com.vinicius741.webnovelarchiver.narration.NarrationCompressionStatus.Phase

/** The words for [NarrationCompressionStatus]: a headline and a detail line, for the Settings and Listen screens. */
internal object NarrationCompressionText {
    data class Lines(
        val headline: String,
        val detail: String,
    )

    /**
     * Null when there is nothing to say. [totalBytes] is all generated audio now; [timeLabel]
     * formats [NarrationCompressionStatus.retryAt].
     */
    fun describe(
        status: NarrationCompressionStatus,
        totalBytes: Long,
        timeLabel: (Long) -> String,
    ): Lines? {
        if (!status.visible) return null
        val left = chapters(status.remainingChapters) + " left (" + NarrationPlanning.formatBytes(status.remainingBytes) + ")"
        val outlook =
            buildList {
                if (status.savedBytes > 0) add("Saved ${NarrationPlanning.formatBytes(status.savedBytes)} so far")
                add("audio ends near ${NarrationPlanning.formatBytes(status.expectedFinalBytes(totalBytes))}")
            }.joinToString(" · ").replaceFirstChar(Char::uppercaseChar)
        return when (status.phase) {
            Phase.CHECKING -> null
            Phase.COMPRESSING -> Lines("Compressing older audio · $left", outlook)
            Phase.WAITING_FOR_GENERATION -> Lines("Compression waits for audio generation · $left", outlook)
            Phase.PAUSED ->
                Lines(
                    "Compression paused after codec errors · $left",
                    "Trying again at ${status.retryAt?.let(timeLabel) ?: "soon"} · $outlook",
                )
            Phase.DONE ->
                if (status.failedChapters > 0) {
                    Lines(
                        "${chapters(status.failedChapters).replaceFirstChar(Char::uppercaseChar)} could not be compressed",
                        "They still play; the app tries again on its next start" +
                            if (status.savedBytes > 0) " · Saved ${NarrationPlanning.formatBytes(status.savedBytes)}" else "",
                    )
                } else {
                    Lines("Older audio compressed", "Saved ${NarrationPlanning.formatBytes(status.savedBytes)}")
                }
        }
    }

    private fun chapters(count: Int) = if (count == 1) "1 chapter" else "$count chapters"
}
