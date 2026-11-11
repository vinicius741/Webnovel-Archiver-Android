package com.vinicius741.webnovelarchiver.data.diagnostics

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicLong

/**
 * File side of [ActivityLog], used only from its single writer thread: appends batches to the
 * day's file, rolls into numbered parts, and prunes by [ActivityLogFiles] retention. Failures are
 * counted, never thrown, and never routed through Timber (the Timber tree feeds this log).
 */
internal class ActivityLogWriter(
    private val directory: File,
) {
    private val failures = AtomicLong(0)
    private var currentDate: LocalDate? = null
    private var currentPart = 0

    fun failures(): Long = failures.get()

    fun start() {
        directory.mkdirs()
        prune(LocalDate.now())
    }

    fun append(lines: List<String>) {
        if (lines.isEmpty()) return
        if (!directory.isDirectory) directory.mkdirs()
        val today = LocalDate.now()
        if (currentDate != today) {
            prune(today)
            currentDate = today
            currentPart = ActivityLogFiles.appendPart(listFiles(), today)
        }
        try {
            var file = File(directory, ActivityLogFiles.fileName(today, currentPart))
            if (file.length() >= ActivityLogFiles.MAX_PART_BYTES) {
                currentPart += 1
                file = File(directory, ActivityLogFiles.fileName(today, currentPart))
                // Enforce the total cap as parts roll over, not only at startup or a new day.
                prune(today)
            }
            FileOutputStream(file, true).bufferedWriter().use { out ->
                lines.forEach { line ->
                    out.write(line)
                    out.write("\n")
                }
            }
        } catch (error: IOException) {
            failures.incrementAndGet()
            Log.w(TAG, "Could not append activity log: ${error.javaClass.simpleName}")
        }
    }

    fun filesOldestFirst(): List<File> = ActivityLogFiles.chronological(listFiles()).map { File(directory, it.name) }

    /** The version recorded by the previous run when it differs from [version]; records [version]. */
    fun previousVersion(version: ActivityLog.AppVersion): String? =
        runCatching {
            val file = File(directory, VERSION_FILE)
            val current = "${version.versionCode}|${version.versionName}"
            val previous = file.takeIf(File::exists)?.readText()?.trim()
            if (previous != current) file.writeText(current)
            previous?.takeIf { it != current }
        }.getOrNull()

    private fun prune(today: LocalDate) {
        runCatching {
            ActivityLogFiles.filesToPrune(listFiles(), today).forEach { name -> File(directory, name).delete() }
        }
    }

    private fun listFiles(): List<ActivityLogFiles.LogFile> =
        directory
            .listFiles()
            .orEmpty()
            .filter(File::isFile)
            .map { ActivityLogFiles.LogFile(it.name, it.length()) }

    private companion object {
        const val TAG = "ActivityLog"
        const val VERSION_FILE = "last_version.txt"
    }
}
