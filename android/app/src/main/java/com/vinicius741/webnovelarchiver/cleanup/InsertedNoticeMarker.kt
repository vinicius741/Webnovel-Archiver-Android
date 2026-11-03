package com.vinicius741.webnovelarchiver.cleanup

/** Chapter HTML with marked notices emptied; [noticesRemoved] is 0 when they were already empty. */
data class InsertedNoticeStrip(
    val html: String,
    val noticesRemoved: Int,
)

/** A source's own markup around the notices it injects into chapter text. */
fun interface InsertedNoticeMarker {
    /**
     * Empties marked notices but keeps the marker, so a later cleanup still recognizes the chapter.
     * Returns null when [html] has no marker and only the sentence list can clean it.
     */
    fun strip(html: String): InsertedNoticeStrip?
}

/**
 * Notices when a source stops marking its notices, which would leave only the sentence list
 * removing them. Some older chapters legitimately lack the marker (the longest run in a real
 * library was 30), so only a long unbroken run of downloads counts.
 */
class InsertedNoticeMarkerMonitor(
    private val alertAfterMissing: Int = DEFAULT_ALERT_AFTER_MISSING,
) {
    private val missingRuns = mutableMapOf<String, Int>()

    /** Returns true once per run, on the download that reaches the alert threshold. */
    @Synchronized
    fun record(
        sourceId: String,
        markerFound: Boolean,
    ): Boolean {
        val run = if (markerFound) 0 else (missingRuns[sourceId] ?: 0) + 1
        missingRuns[sourceId] = run
        return run == alertAfterMissing
    }

    private companion object {
        const val DEFAULT_ALERT_AFTER_MISSING = 50
    }
}
