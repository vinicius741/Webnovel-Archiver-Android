package com.vinicius741.webnovelarchiver.tts.kokoro

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Downloads, checks and unpacks the Kokoro model into app files. One install runs at a time on the
 * app scope, so it survives leaving the settings screen; a stopped download resumes where it left
 * off. The model only becomes visible once fully unpacked and checked (see [KokoroModelFiles]).
 */
internal class KokoroModelInstaller(
    context: Context,
    private val scope: CoroutineScope,
    private val client: OkHttpClient = defaultClient(),
) {
    private val files = KokoroModelFiles(context)
    private val _state = MutableStateFlow(currentState())
    val state: StateFlow<KokoroModelState> get() = _state
    private var job: Job? = null

    /** From the published state, so the UI and playback never touch the disk to ask. */
    val isInstalled: Boolean get() = _state.value is KokoroModelState.Installed

    val isRunning: Boolean get() = job?.isActive == true

    fun install() {
        if (isRunning || isInstalled) return
        job =
            scope.launch(Dispatchers.IO) {
                runCatching { installNow() }
                    .onFailure { error ->
                        if (error is kotlinx.coroutines.CancellationException) {
                            _state.value = currentState()
                            throw error
                        }
                        Timber.w(error, "Kokoro model install failed")
                        _state.value = KokoroModelState.Failed(failureText(error))
                    }
            }
    }

    /** Stops a running install; what was downloaded is kept for the next try. */
    fun cancel() {
        // The install's own cancellation handler publishes the resulting state, off this thread.
        job?.cancel()
        job = null
    }

    /** Deletes the model and any partial download. A voice already loaded keeps working until released. */
    fun remove() {
        val running = job
        job = null
        scope.launch(Dispatchers.IO) {
            // Let a running install stop writing before its files are deleted.
            running?.cancelAndJoin()
            files.dir.deleteRecursively()
            files.staging.deleteRecursively()
            files.partialArchive.delete()
            _state.value = currentState()
        }
    }

    private fun currentState(): KokoroModelState =
        if (files.isInstalled) {
            KokoroModelState.Installed(files.installedBytes())
        } else {
            KokoroModelState.NotInstalled(files.partialArchive.length())
        }

    private suspend fun installNow() {
        files.modelsDir.mkdirs()
        val resumeFrom = KokoroInstallPlanning.resumeFrom(files.partialArchive.length())
        if (files.modelsDir.usableSpace < KokoroInstallPlanning.bytesNeeded(resumeFrom)) {
            throw InstallException(
                "Not enough free space. Kokoro needs about " +
                    "${KokoroInstallPlanning.megabytes(KokoroInstallPlanning.bytesNeeded(resumeFrom))} free to install.",
            )
        }
        // A complete archive left by an interrupted unpack is checked and reused, not fetched again.
        if (files.partialArchive.length() != KokoroModel.ARCHIVE_BYTES) download(resumeFrom)
        _state.value = KokoroModelState.Verifying
        if (sha256(files.partialArchive) != KokoroModel.ARCHIVE_SHA256) {
            files.partialArchive.delete()
            throw InstallException("The download was damaged. Try again.")
        }
        unpack()
        files.partialArchive.delete()
        _state.value = currentState()
    }

    private suspend fun download(resumeFrom: Long) {
        val request =
            Request
                .Builder()
                .url(KokoroModel.ARCHIVE_URL)
                .apply { if (resumeFrom > 0L) header("Range", "bytes=$resumeFrom-") }
                .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw InstallException("The download failed (HTTP ${response.code}). Try again later.")
            // A server that ignores the range sends the whole file again.
            val append = response.code == HTTP_PARTIAL && resumeFrom > 0L
            var written = if (append) resumeFrom else 0L
            FileOutputStream(files.partialArchive, append).use { out ->
                copy(response.body.byteStream(), out) { count ->
                    written += count
                    _state.value = KokoroModelState.Downloading(written, KokoroModel.ARCHIVE_BYTES)
                }
            }
            if (written != KokoroModel.ARCHIVE_BYTES) throw InstallException("The download stopped early. Try again to continue it.")
        }
    }

    private suspend fun unpack() {
        val staging = files.staging
        staging.deleteRecursively()
        staging.mkdirs()
        var unpacked = 0L
        _state.value = KokoroModelState.Unpacking(0L, KokoroModel.UNPACKED_BYTES)
        val archive = BufferedInputStream(files.partialArchive.inputStream(), BUFFER_BYTES)
        TarArchiveInputStream(BZip2CompressorInputStream(archive)).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                unpackEntry(tar, entry, staging) { count ->
                    unpacked += count
                    _state.value = KokoroModelState.Unpacking(unpacked, KokoroModel.UNPACKED_BYTES)
                }
            }
        }
        finishUnpack(staging)
    }

    private suspend fun unpackEntry(
        tar: TarArchiveInputStream,
        entry: TarArchiveEntry,
        staging: File,
        onProgress: (Int) -> Unit,
    ) {
        val target =
            KokoroInstallPlanning.entryTarget(staging, entry.name)
                ?: throw InstallException("The download contained unexpected files.")
        when {
            entry.isDirectory -> target.mkdirs()
            entry.isFile -> {
                target.parentFile?.mkdirs()
                FileOutputStream(target).use { out -> copy(tar, out, onProgress) }
            }
            // Links and devices have no place in a model; skip them rather than trust them.
            else -> Unit
        }
    }

    /** Checks the unpacked files, marks them complete, and swaps them in for any older copy. */
    private fun finishUnpack(staging: File) {
        if (KokoroModel.requiredFiles.any { !File(staging, it).isFile }) throw InstallException("The download was incomplete. Try again.")
        File(staging, KokoroModelFiles.INSTALLED_MARKER).writeText(KokoroModel.ARCHIVE_SHA256)
        files.dir.deleteRecursively()
        if (!staging.renameTo(files.dir)) throw InstallException("Could not finish installing. Try again.")
    }

    private suspend fun copy(
        input: InputStream,
        output: FileOutputStream,
        onProgress: (Int) -> Unit,
    ) {
        val buffer = ByteArray(BUFFER_BYTES)
        var sinceReport = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            sinceReport += read
            if (sinceReport >= REPORT_EVERY_BYTES) {
                onProgress(sinceReport)
                sinceReport = 0
            }
        }
        if (sinceReport > 0) onProgress(sinceReport)
    }

    private fun failureText(error: Throwable): String =
        when (error) {
            is InstallException -> error.message.orEmpty()
            is IOException -> "The download was interrupted. Check the connection and try again to continue it."
            else -> "Kokoro could not be installed. Try again."
        }

    private class InstallException(
        message: String,
    ) : IOException(message)

    private companion object {
        const val HTTP_PARTIAL = 206
        const val BUFFER_BYTES = 256 * 1024
        const val REPORT_EVERY_BYTES = 2 * 1024 * 1024

        fun defaultClient(): OkHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
    }
}

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(SHA_BUFFER_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private const val SHA_BUFFER_BYTES = 256 * 1024
