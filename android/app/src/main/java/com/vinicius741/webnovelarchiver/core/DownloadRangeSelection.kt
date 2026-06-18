package com.vinicius741.webnovelarchiver.core

object DownloadRangeSelection {
    const val DEFAULT_COUNT = 150

    enum class Mode {
        RANGE,
        BOOKMARK,
        COUNT,
    }

    data class Result(
        val indexes: List<Int> = emptyList(),
        val startChapter: Int? = null,
        val endChapter: Int? = null,
        val error: String? = null,
    ) {
        val valid: Boolean
            get() = error == null
    }

    fun select(
        mode: Mode,
        totalChapters: Int,
        rangeStart: Int?,
        rangeEnd: Int?,
        countStart: Int?,
        count: Int?,
        bookmarkChapterNumber: Int?,
    ): Result {
        if (totalChapters <= 0) return Result(error = "No chapters available.")

        return when (mode) {
            Mode.RANGE -> {
                val start = rangeStart ?: return Result(error = "Please enter valid numbers.")
                val end = rangeEnd ?: return Result(error = "Please enter valid numbers.")
                when {
                    start < 1 || end > totalChapters -> Result(error = "Range must be between 1 and $totalChapters.")
                    start > end -> Result(error = "Start chapter cannot be greater than end chapter.")
                    else -> resultForRange(start, end)
                }
            }
            Mode.BOOKMARK -> {
                val bookmark = bookmarkChapterNumber ?: return Result(error = "No bookmark found for this story.")
                val start = bookmark + 1
                if (start > totalChapters) return Result(error = "Bookmark is at the last chapter, nothing to download.")
                val chapterCount = count ?: return Result(error = "Please enter a valid number of chapters.")
                if (chapterCount < 1) return Result(error = "Please enter a valid number of chapters.")
                resultForRange(start, minOf(start + chapterCount - 1, totalChapters))
            }
            Mode.COUNT -> {
                val start = countStart ?: return Result(error = "Please enter valid numbers.")
                val chapterCount = count ?: return Result(error = "Please enter valid numbers.")
                when {
                    start < 1 || start > totalChapters -> Result(error = "Start chapter must be between 1 and $totalChapters.")
                    chapterCount < 1 -> Result(error = "Chapter count must be at least 1.")
                    else -> resultForRange(start, minOf(start + chapterCount - 1, totalChapters))
                }
            }
        }
    }

    private fun resultForRange(
        startChapter: Int,
        endChapter: Int,
    ): Result =
        Result(
            indexes = (startChapter - 1 until endChapter).toList(),
            startChapter = startChapter,
            endChapter = endChapter,
        )
}
