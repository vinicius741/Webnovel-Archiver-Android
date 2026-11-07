package com.vinicius741.webnovelarchiver.data.diagnostics

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.vinicius741.webnovelarchiver.data.storage.SharedGson
import java.io.File
import java.io.Writer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * Persistent, append-only diagnostic record store shared by every diagnostic producer (source
 * access events, warnings/errors, operation timings, crashes, process starts). Records are JSON
 * Lines in rotating segment files under `filesDir/diagnostics/`, outside the library root so a
 * full-backup restore never swaps them away and they are never part of a backup.
 *
 * Persistence is what makes exports useful: the previous in-memory rings lost everything when
 * the process died, which is exactly when a user needs to report a problem. Exports read a
 * time window (see [DiagnosticWindow]) instead of "everything since process start".
 *
 * Writes are handed to one daemon writer thread so call sites (network threads, the main thread
 * logging a warning) never touch disk. [appendBlocking] is the synchronous crash path.
 * The journal must never log through Timber: Timber's diagnostic tree writes here, so a failure
 * report would recurse. Failures are counted instead and surfaced in exports.
 */
class DiagnosticJournal(
    private val directory: File,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val maxSegmentBytes: Long = MAX_SEGMENT_BYTES,
    private val maxTotalBytes: Long = MAX_TOTAL_BYTES,
    private val retentionMillis: Long = RETENTION_MILLIS,
    background: Boolean = true,
) {
    /** Short per-process id stamped on every record so exports can separate app runs. */
    val sessionId: String =
        java.lang.Long
            .toString(nowMillis(), 36)
            .takeLast(6)

    private val lock = Any()
    private val pending = LinkedBlockingQueue<String>(MAX_PENDING_RECORDS)
    private val writeFailures = AtomicLong(0)
    private val droppedRecords = AtomicLong(0)
    private var writer: Writer? = null
    private var segment: File? = null
    private var segmentBytes = 0L
    private var segmentCounter = 0
    private val synchronous = !background

    init {
        if (background) {
            Thread(::drainLoop, "wna-diagnostic-journal")
                .apply {
                    isDaemon = true
                    priority = Thread.MIN_PRIORITY
                }.start()
        }
    }

    /** Queues one record. The map is serialized on the caller's thread so later mutation is harmless. */
    fun append(record: Map<String, Any?>) {
        val line = serialize(record) ?: return
        if (synchronous) {
            synchronized(lock) {
                writeLocked(line)
                runCatching { writer?.flush() }.onFailure { writeFailures.incrementAndGet() }
            }
            return
        }
        if (!pending.offer(line)) droppedRecords.incrementAndGet()
    }

    /** Crash path: drains queued records, writes [record], and fsyncs before returning. */
    fun appendBlocking(record: Map<String, Any?>) {
        val line = serialize(record) ?: return
        synchronized(lock) {
            drainPendingLocked()
            writeLocked(line)
            runCatching {
                writer?.flush()
                segment?.let { file -> java.io.FileOutputStream(file, true).use { it.fd.sync() } }
            }.onFailure { writeFailures.incrementAndGet() }
        }
    }

    /** Writes everything queued so far to disk. Exports call this before reading. */
    fun flush() {
        synchronized(lock) {
            drainPendingLocked()
            runCatching { writer?.flush() }.onFailure { writeFailures.incrementAndGet() }
        }
    }

    /**
     * Streams every readable record with `ts >= sinceMillis`, oldest first. Malformed lines (a
     * write torn by process death) are skipped. Callers keep only what they need so a full
     * retention window never has to fit in memory at once.
     */
    fun forEachRecord(
        sinceMillis: Long,
        consumer: (JsonObject) -> Unit,
    ) {
        flush()
        journalSegments(directory)
            .filter { it.lastModified() >= sinceMillis }
            .forEach { file ->
                runCatching {
                    file.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            if (line.isBlank()) return@forEach
                            val record = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull() ?: return@forEach
                            val timestamp = runCatching { record.get("ts")?.asLong }.getOrNull() ?: return@forEach
                            if (timestamp >= sinceMillis) consumer(record)
                        }
                    }
                }
            }
    }

    fun stats(): DiagnosticJournalStats {
        flush()
        val segments = journalSegments(directory)
        return DiagnosticJournalStats(
            sessionId = sessionId,
            segmentCount = segments.size,
            totalBytes = segments.sumOf(File::length),
            retentionHours = retentionMillis / HOUR_MILLIS,
            writeFailures = writeFailures.get(),
            droppedRecords = droppedRecords.get(),
        )
    }

    private fun serialize(record: Map<String, Any?>): String? =
        runCatching { SharedGson.plain.toJson(record) }
            .onFailure { writeFailures.incrementAndGet() }
            .getOrNull()

    private fun drainLoop() {
        while (true) {
            val first =
                try {
                    pending.take()
                } catch (_: InterruptedException) {
                    return
                }
            synchronized(lock) {
                writeLocked(first)
                drainPendingLocked()
                runCatching { writer?.flush() }.onFailure { writeFailures.incrementAndGet() }
            }
        }
    }

    private fun drainPendingLocked() {
        while (true) {
            val line = pending.poll() ?: return
            writeLocked(line)
        }
    }

    private fun writeLocked(line: String) {
        try {
            if (writer == null || segmentBytes >= maxSegmentBytes) rotateLocked()
            val bytes = line.toByteArray().size + 1
            writer!!.write(line)
            writer!!.write("\n")
            segmentBytes += bytes
        } catch (_: Exception) {
            writeFailures.incrementAndGet()
            runCatching { writer?.close() }
            writer = null
        }
    }

    private fun rotateLocked() {
        directory.mkdirs()
        runCatching { writer?.close() }
        val file =
            File(directory, "$SEGMENT_PREFIX${nowMillis().toString().padStart(13, '0')}_${sessionId}_${segmentCounter++}$SEGMENT_SUFFIX")
        segment = file
        segmentBytes = file.length()
        writer = java.io.FileOutputStream(file, true).bufferedWriter()
        pruneLocked()
    }

    /** Deletes segments past retention, then oldest segments until the total fits the size cap. */
    private fun pruneLocked() {
        val cutoff = nowMillis() - retentionMillis
        val segments = journalSegments(directory).toMutableList()
        segments.removeAll { file -> (file != segment && file.lastModified() < cutoff).also { expired -> if (expired) file.delete() } }
        var total = segments.sumOf(File::length)
        for (file in segments) {
            if (total <= maxTotalBytes) break
            if (file == segment) continue
            total -= file.length()
            file.delete()
        }
    }

    companion object {
        const val HOUR_MILLIS = 60L * 60L * 1000L
        const val RETENTION_MILLIS = 72L * HOUR_MILLIS
        const val MAX_SEGMENT_BYTES = 1024L * 1024L
        const val MAX_TOTAL_BYTES = 12L * 1024L * 1024L
        private const val MAX_PENDING_RECORDS = 10_000

        /** Installed once by the Application; null in unit tests, where recording is a no-op. */
        @Volatile var active: DiagnosticJournal? = null
    }
}

data class DiagnosticJournalStats(
    val sessionId: String,
    val segmentCount: Int,
    val totalBytes: Long,
    val retentionHours: Long,
    val writeFailures: Long,
    val droppedRecords: Long,
)

/** Record kinds (`k`) written to the journal. */
object DiagnosticRecordKind {
    const val SESSION = "session"
    const val LOG = "log"
    const val OPERATION = "op"
    const val SOURCE = "src"
    const val CRASH = "crash"
}

/** The time windows a user can export; the journal retains [DiagnosticJournal.RETENTION_MILLIS]. */
enum class DiagnosticWindow(
    val label: String,
    val hours: Long,
) {
    LAST_HOUR("Last hour", 1),
    LAST_6_HOURS("Last 6 hours", 6),
    LAST_24_HOURS("Last 24 hours", 24),
    LAST_3_DAYS("Last 3 days", 72),
    ;

    val millis: Long get() = hours * DiagnosticJournal.HOUR_MILLIS
}

/** Segment files, oldest first: names embed a zero-padded start time, so name order is time order. */
private fun journalSegments(directory: File): List<File> =
    directory
        .listFiles { file -> file.isFile && file.name.startsWith(SEGMENT_PREFIX) && file.name.endsWith(SEGMENT_SUFFIX) }
        ?.sortedBy(File::getName)
        .orEmpty()

private const val SEGMENT_PREFIX = "journal_"
private const val SEGMENT_SUFFIX = ".jsonl"
