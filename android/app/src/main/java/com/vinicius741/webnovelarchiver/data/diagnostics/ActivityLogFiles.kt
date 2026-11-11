package com.vinicius741.webnovelarchiver.data.diagnostics

import java.time.LocalDate

/**
 * Pure naming and retention rules for the activity log's daily files:
 * `activity_<yyyy-MM-dd>.ndjson`, then `activity_<date>_<part>.ndjson` once a day's file is full.
 */
object ActivityLogFiles {
    const val FILE_PREFIX = "activity_"
    const val FILE_SUFFIX = ".ndjson"

    /** Days of history kept, today included. */
    const val RETAIN_DAYS = 7
    const val MAX_TOTAL_BYTES = 8L * 1024 * 1024

    /** A day's file rolls into a numbered part past this size, so pruning can drop whole parts. */
    const val MAX_PART_BYTES = 1L * 1024 * 1024

    fun fileName(
        date: LocalDate,
        part: Int,
    ): String = if (part == 0) "$FILE_PREFIX$date$FILE_SUFFIX" else "$FILE_PREFIX${date}_$part$FILE_SUFFIX"

    data class LogFileName(
        val date: LocalDate,
        val part: Int,
    ) : Comparable<LogFileName> {
        override fun compareTo(other: LogFileName): Int = compareValuesBy(this, other, LogFileName::date, LogFileName::part)
    }

    fun parseFileName(name: String): LogFileName? {
        if (!name.startsWith(FILE_PREFIX) || !name.endsWith(FILE_SUFFIX)) return null
        val stem = name.removePrefix(FILE_PREFIX).removeSuffix(FILE_SUFFIX)
        val datePart = stem.substringBefore('_')
        val part = if ('_' in stem) stem.substringAfter('_').toIntOrNull() ?: return null else 0
        val date = runCatching { LocalDate.parse(datePart) }.getOrNull() ?: return null
        return LogFileName(date, part)
    }

    data class LogFile(
        val name: String,
        val sizeBytes: Long,
    )

    /** Log file names oldest first; anything that isn't a log file is left out. */
    fun chronological(files: List<LogFile>): List<LogFile> =
        files.mapNotNull { file -> parseFileName(file.name)?.let { it to file } }.sortedBy { it.first }.map { it.second }

    /**
     * Files to delete: anything older than [retainDays] (today counts as one), then the oldest
     * remaining files until the total fits [maxTotalBytes]. The newest file always survives.
     */
    fun filesToPrune(
        files: List<LogFile>,
        today: LocalDate,
        retainDays: Int = RETAIN_DAYS,
        maxTotalBytes: Long = MAX_TOTAL_BYTES,
    ): List<String> {
        val ordered = chronological(files)
        val oldestKept = today.minusDays((retainDays - 1).toLong())
        val prune = mutableListOf<String>()
        val remaining = ArrayDeque<LogFile>()
        ordered.forEach { file ->
            val date = parseFileName(file.name)?.date
            if (date != null && date < oldestKept) prune += file.name else remaining.addLast(file)
        }
        var total = remaining.sumOf { it.sizeBytes }
        while (remaining.size > 1 && total > maxTotalBytes) {
            val dropped = remaining.removeFirst()
            prune += dropped.name
            total -= dropped.sizeBytes
        }
        return prune
    }

    /** The part to append to for [date]: the newest existing part unless it is full. */
    fun appendPart(
        files: List<LogFile>,
        date: LocalDate,
        maxPartBytes: Long = MAX_PART_BYTES,
    ): Int {
        val latest =
            files
                .mapNotNull { file -> parseFileName(file.name)?.takeIf { it.date == date }?.let { it to file } }
                .maxByOrNull { it.first.part }
                ?: return 0
        return if (latest.second.sizeBytes >= maxPartBytes) latest.first.part + 1 else latest.first.part
    }
}
