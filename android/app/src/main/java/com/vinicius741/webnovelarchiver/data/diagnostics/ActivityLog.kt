package com.vinicius741.webnovelarchiver.data.diagnostics

import android.util.Log
import com.vinicius741.webnovelarchiver.data.storage.SharedGson
import timber.log.Timber
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

enum class ActivityCategory(
    val wire: String,
) {
    APP("app"),
    SYNC("sync"),
    DOWNLOAD("dl"),
    LIBRARY("library"),
    BACKUP("backup"),
    RECOVERY("recovery"),
    READER("reader"),
    TTS("tts"),
    NET("net"),
    LOG("log"),
}

/**
 * Persistent, structured record of what the app did over the last few days (syncs, downloads,
 * library changes, backups, network access, warnings, crashes), exported from Settings → Share
 * Activity Logs so a problem on the owner's phone can be diagnosed from the file alone.
 *
 * Unlike [BypassEventLog], events may carry story/chapter ids and titles. They never carry
 * cookies, auth headers, API keys, session tokens, or chapter text; [ActivityLogPlanning] scrubs
 * values as a backstop.
 *
 * [record] never throws and never touches disk on the caller's thread: lines queue in memory and
 * one background thread ([ActivityLogWriter]) appends them to `files/activity_logs/`. That
 * directory sits outside the `webnovel_archiver` storage root, so Clear Local Storage and a
 * full-backup restore (which replace that root) keep the history that explains them. Events
 * recorded before [init] (or in JVM unit tests, where it is never called) wait in a bounded buffer.
 */
object ActivityLog {
    private const val MAX_PENDING_LINES = 5000

    private val gson = SharedGson.plain
    private val pending = ConcurrentLinkedQueue<String>()
    private val pendingCount = AtomicInteger(0)
    private val droppedBeforeWrite = AtomicLong(0)
    private val sequence = AtomicLong(0)
    private val drainScheduled = AtomicBoolean(false)
    private val recordFailures = AtomicLong(0)
    private val sessionId: String = UUID.randomUUID().toString().take(8)

    @Volatile private var executor: ExecutorService? = null

    @Volatile private var writer: ActivityLogWriter? = null

    data class AppVersion(
        val versionName: String,
        val versionCode: Long,
        val buildType: String,
        val sdkInt: Int,
        val device: String,
        val pid: Int,
    )

    data class Stats(
        val sessionId: String,
        val droppedBeforeWrite: Long,
        val writeFailures: Long,
    )

    fun record(
        category: ActivityCategory,
        type: String,
        vararg fields: Pair<String, Any?>,
    ) = recordAll(category, type, fields.asList())

    /** [record] for callers that assemble their fields as a list. Logging must never break the caller. */
    fun recordAll(
        category: ActivityCategory,
        type: String,
        fields: List<Pair<String, Any?>>,
    ) {
        runCatching {
            val map =
                ActivityLogPlanning.eventMap(
                    sequence.incrementAndGet(),
                    System.currentTimeMillis(),
                    sessionId,
                    category,
                    type,
                    fields,
                )
            enqueue(gson.toJson(map))
        }.onFailure { recordFailures.incrementAndGet() }
    }

    /** Starts the writer, prunes old files, records the process start and upgrades, and hooks crashes. */
    @Synchronized
    fun init(
        logDirectory: File,
        version: AppVersion,
    ) {
        if (executor != null) return
        val fileWriter = ActivityLogWriter(logDirectory)
        writer = fileWriter
        executor =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "activity-log-writer").apply {
                    isDaemon = true
                    priority = Thread.MIN_PRIORITY
                }
            }
        // Create the directory before the first drain: record() below schedules one, and on a
        // fresh install a drain that ran first would find no directory and drop its batch.
        executor?.execute { fileWriter.start() }
        record(
            ActivityCategory.APP,
            "process_start",
            "versionName" to version.versionName,
            "versionCode" to version.versionCode,
            "buildType" to version.buildType,
            "sdk" to version.sdkInt,
            "device" to version.device,
            "pid" to version.pid,
            "bufferedBeforeStart" to pendingCount.get(),
        )
        executor?.execute {
            fileWriter.previousVersion(version)?.let { previous ->
                record(
                    ActivityCategory.APP,
                    "app_upgraded",
                    "from" to previous.substringAfter('|'),
                    "fromCode" to previous.substringBefore('|').toLongOrNull(),
                    "to" to version.versionName,
                    "toCode" to version.versionCode,
                )
            }
        }
        installActivityLogCrashHandler()
        scheduleDrain()
    }

    fun stats(): Stats = Stats(sessionId, droppedBeforeWrite.get(), recordFailures.get() + (writer?.failures() ?: 0))

    /** Log files oldest first, for the exporter. */
    fun logFilesOldestFirst(): List<File> = writer?.filesOldestFirst().orEmpty()

    /** Blocks until every line queued so far is on disk, or [timeoutMillis] passes. */
    fun flushBlocking(timeoutMillis: Long = 3000L): Boolean {
        val running = executor ?: return false
        return runCatching {
            running.submit { drain() }.get(timeoutMillis, TimeUnit.MILLISECONDS)
            true
        }.getOrDefault(false)
    }

    private fun enqueue(line: String) {
        pending.add(line)
        // Writer stalled or not started yet: drop the oldest so memory stays bounded.
        if (pendingCount.incrementAndGet() > MAX_PENDING_LINES && pending.poll() != null) {
            pendingCount.decrementAndGet()
            droppedBeforeWrite.incrementAndGet()
        }
        scheduleDrain()
    }

    private fun scheduleDrain() {
        val running = executor ?: return
        if (!drainScheduled.compareAndSet(false, true)) return
        runCatching {
            running.execute {
                drainScheduled.set(false)
                drain()
            }
        }.onFailure { drainScheduled.set(false) }
    }

    /** Writer thread only. */
    private fun drain() {
        val fileWriter = writer ?: return
        val batch = mutableListOf<String>()
        while (true) {
            val line = pending.poll() ?: break
            pendingCount.decrementAndGet()
            batch += line
        }
        fileWriter.append(batch)
    }

    /** Removes and returns the buffered lines; unit tests never call [init], so lines stay buffered. */
    internal fun drainPendingForTest(): List<String> {
        val lines = generateSequence { pending.poll() }.toList()
        pendingCount.set(0)
        return lines
    }
}

private const val CRASH_FLUSH_TIMEOUT_MS = 1500L

/** Records an uncaught exception and flushes it to disk before the previous handler kills the process. */
private fun installActivityLogCrashHandler() {
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        runCatching {
            ActivityLog.record(
                ActivityCategory.APP,
                "crash",
                "thread" to thread.name,
                "error" to error,
                "stack" to ActivityLogPlanning.shortStack(error, maxLines = 30),
            )
            ActivityLog.flushBlocking(CRASH_FLUSH_TIMEOUT_MS)
        }
        previous?.uncaughtException(thread, error)
    }
}

/** Mirrors WARN+ Timber logs (tag, message, exception) into [ActivityLog]. */
class ActivityLogTree : Timber.Tree() {
    override fun isLoggable(
        tag: String?,
        priority: Int,
    ): Boolean = priority >= Log.WARN

    override fun log(
        priority: Int,
        tag: String?,
        message: String,
        t: Throwable?,
    ) {
        ActivityLog.record(
            ActivityCategory.LOG,
            if (priority >= Log.ERROR) "error" else "warn",
            "tag" to tag,
            // Timber appends the stack trace to the message; keep only the message part.
            "message" to message.substringBefore("\n"),
            "error" to t,
            "stack" to t?.let { ActivityLogPlanning.shortStack(it) },
        )
    }
}
