package com.vinicius741.webnovelarchiver.feature.story

/** Tallies from one "Apply text cleanup" run over a story's downloaded chapters. */
internal data class CleanupRunStats(
    val totalChapters: Int,
    val processedChapters: Int,
    val failedChapters: Int,
    val changedChapters: Int,
    val sentencesRemoved: Int,
    val regexMatchesRemoved: Int,
)

internal data class CleanupSummary(
    val title: String,
    val message: String,
)

/** Builds the user-facing result dialog for a text cleanup run. */
internal object CleanupSummaryPlanning {
    fun summarize(stats: CleanupRunStats): CleanupSummary {
        val removals = stats.sentencesRemoved + stats.regexMatchesRemoved
        val title =
            when {
                stats.failedChapters > 0 -> "Text Cleanup Finished with Errors"
                stats.changedChapters == 0 -> "Text Cleanup: Nothing to Remove"
                else -> "Text Cleanup Complete"
            }
        val paragraphs = mutableListOf<String>()
        paragraphs +=
            if (stats.failedChapters > 0) {
                "Checked ${stats.processedChapters} of ${chapters(stats.totalChapters)}. " +
                    "${chapters(stats.failedChapters)} couldn't be processed because " +
                    "${if (stats.failedChapters == 1) "its file is" else "their files are"} missing or unreadable."
            } else {
                "Checked ${chapters(stats.processedChapters)} against your sentence removal list and regex cleanup rules."
            }
        paragraphs +=
            when {
                removals > 0 ->
                    "Removed ${plural(removals, "match", "matches")} from ${chapters(stats.changedChapters)}:\n" +
                        "• ${plural(stats.sentencesRemoved, "sentence", "sentences")} from the sentence removal list\n" +
                        "• ${plural(stats.regexMatchesRemoved, "regex rule match", "regex rule matches")}"
                stats.changedChapters > 0 ->
                    "No rule matched any text, but leftover formatting was cleaned up in " +
                        "${chapters(stats.changedChapters)}."
                else -> "No rule matched any text, so no chapters were changed."
            }
        if (stats.changedChapters > 0) {
            paragraphs += "Regenerate the EPUB to include these changes."
        }
        return CleanupSummary(title, paragraphs.joinToString("\n\n"))
    }

    private fun chapters(count: Int): String = plural(count, "chapter", "chapters")

    private fun plural(
        count: Int,
        singular: String,
        pluralForm: String,
    ): String = "$count ${if (count == 1) singular else pluralForm}"
}
