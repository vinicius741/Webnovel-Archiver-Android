package com.vinicius741.webnovelarchiver.tts.kokoro

import android.content.Context
import kotlinx.coroutines.CancellationException
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
 * Downloads, checks and installs one built-in model ([builtIn]) into app files: a sherpa-onnx archive
 * is unpacked, Phonon's separate files are each checked. One install runs at a time on the app scope,
 * so it survives leaving the settings screen; a stopped download resumes where it left off. The model
 * only becomes visible once complete and checked (see [KokoroModelFiles]).
 */
@Suppress("TooManyFunctions") // One installer for both kinds of package, archive and separate files.
internal class KokoroModelInstaller(
    context: Context,
    private val scope: CoroutineScope,
    private val client: OkHttpClient = defaultClient(),
    val builtIn: BuiltInModel = BuiltInModel.KOKORO_V019,
) {
    private val files = KokoroModelFiles(context, builtIn)
    private val pkg = builtIn.modelPackage
    private val _state = MutableStateFlow(currentState())
    val state: StateFlow<KokoroModelState> get() = _state

    /** The latest install. A cancelled one is kept until it has stopped, so a new one can wait for it. */
    @Volatile
    private var job: Job? = null

    /** From the published state, so the UI and playback never touch the disk to ask. */
    val isInstalled: Boolean get() = _state.value is KokoroModelState.Installed

    val isRunning: Boolean get() = job?.isActive == true

    fun install() {
        if (isRunning || isInstalled) return
        // A cancelled install may still be blocked in a read; let it stop before writing again.
        val previous = job
        job =
            scope.launch(Dispatchers.IO) {
                previous?.cancelAndJoin()
                val self = coroutineContext[Job]
                runCatching { installNow() }
                    .onFailure { error ->
                        if (error is CancellationException) {
                            // A replacement install publishes its own progress; a stale one must not.
                            if (job === self) _state.value = currentState()
                            throw error
                        }
                        Timber.w(error, "%s model install failed", builtIn.dirName)
                        _state.value = KokoroModelState.Failed(failureText(error, builtIn))
                    }
            }
    }

    /** Stops a running install; what was downloaded is kept for the next try. */
    fun cancel() {
        // The install's own cancellation handler publishes the resulting state, off this thread.
        job?.cancel()
    }

    /** Deletes the model and any partial download. A voice already loaded keeps working until released. */
    fun remove() {
        val running = job
        // Held as the current job, so an install started meanwhile waits for the files to go.
        job =
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
            KokoroModelState.NotInstalled(downloadedBytes(), files.missingFromOlderInstall())
        }

    /** What a stopped download kept: the partial archive, or the files already in staging. */
    private fun downloadedBytes(): Long =
        when (pkg) {
            is ModelPackage.Archive -> files.partialArchive.length()
            is ModelPackage.Files ->
                files.staging
                    .walkBottomUp()
                    .filter { it.isFile }
                    .sumOf { it.length() }
        }

    /** Bytes the install will not download: what staging holds, plus an older install's files it keeps. */
    private fun reusableBytes(): Long =
        when (pkg) {
            is ModelPackage.Archive -> KokoroInstallPlanning.reusableBytes(downloadedBytes(), pkg.downloadBytes)
            is ModelPackage.Files -> files.reusableFileBytes(pkg)
        }

    private suspend fun installNow() {
        files.modelsDir.mkdirs()
        val needed = KokoroInstallPlanning.bytesNeeded(reusableBytes().coerceIn(0L, pkg.downloadBytes), pkg)
        if (files.modelsDir.usableSpace < needed) {
            throw InstallException(
                "Not enough free space. ${builtIn.family} needs about ${KokoroInstallPlanning.megabytes(needed)} free to install.",
            )
        }
        when (pkg) {
            is ModelPackage.Archive -> installArchive(pkg)
            is ModelPackage.Files -> installFiles(pkg)
        }
        _state.value = currentState()
    }

    private suspend fun installArchive(archive: ModelPackage.Archive) {
        val resumeFrom = KokoroInstallPlanning.resumeFrom(files.partialArchive.length(), archive.downloadBytes)
        // A complete archive left by an interrupted unpack is checked and reused, not fetched again.
        if (files.partialArchive.length() != archive.downloadBytes) {
            download(archive.url, files.partialArchive, resumeFrom, archive.downloadBytes, archive.downloadBytes, 0L)
        }
        _state.value = KokoroModelState.Verifying
        if (sha256(files.partialArchive) != archive.sha256) {
            files.partialArchive.delete()
            throw InstallException("The download was damaged. Try again.")
        }
        unpack(archive)
        files.partialArchive.delete()
    }

    /**
     * Downloads each file into staging (a stopped one resumes), checks it, then marks the folder
     * complete and swaps it in. Files already complete in staging are checked, not fetched again.
     */
    private suspend fun installFiles(set: ModelPackage.Files) {
        files.staging.mkdirs()
        var done = 0L
        for (file in set.files) {
            val target = fetch(file, set.downloadBytes, done)
            if (sha256(target) != file.sha256) {
                target.delete()
                throw InstallException("The download was damaged. Try again.")
            }
            done += file.bytes
            _state.value = KokoroModelState.Downloading(done, set.downloadBytes)
        }
        _state.value = KokoroModelState.Verifying
        File(files.staging, KokoroModelFiles.INSTALLED_MARKER).writeText(set.files.joinToString("\n") { it.sha256 })
        files.dir.deleteRecursively()
        if (!files.staging.renameTo(files.dir)) throw InstallException("Could not finish installing. Try again.")
    }

    /** [file] in staging, downloaded unless already complete there; a stopped download resumes. */
    private suspend fun fetch(
        file: ModelFile,
        total: Long,
        before: Long,
    ): File {
        val target = File(files.staging, file.path)
        if (target.length() == file.bytes) return target
        target.delete()
        target.parentFile?.mkdirs()
        // An older install's copy (only new voices are missing) moves over and is checked like a download.
        val installed = File(files.dir, file.path)
        if (installed.length() == file.bytes && installed.renameTo(target)) return target
        val partial = File(files.staging, "${file.path}.part")
        val resumeFrom = KokoroInstallPlanning.resumeFrom(partial.length(), file.bytes)
        if (partial.length() != file.bytes) download(file.url, partial, resumeFrom, file.bytes, total, before)
        if (!partial.renameTo(target)) throw InstallException("Could not save the download. Try again.")
        return target
    }

    /**
     * Fetches [url] into [target] from [resumeFrom] until it holds [expected] bytes, reporting progress
     * as part of an install of [total] bytes of which [before] were already done.
     */
    private suspend fun download(
        url: String,
        target: File,
        resumeFrom: Long,
        expected: Long,
        total: Long,
        before: Long,
    ) {
        val request =
            Request
                .Builder()
                .url(url)
                .apply { if (resumeFrom > 0L) header("Range", "bytes=$resumeFrom-") }
                .build()
        val call = client.newCall(request)
        // Cancelling the install aborts a read that would otherwise block until the timeout.
        val abort = currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
        var written = 0L
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw InstallException("The download failed (HTTP ${response.code}). Try again later.")
                // A server that ignores the range sends the whole file again.
                val append = response.code == HTTP_PARTIAL && resumeFrom > 0L
                written = if (append) resumeFrom else 0L
                FileOutputStream(target, append).use { out ->
                    copyWithProgress(response.body.byteStream(), out) { count ->
                        written += count
                        _state.value = KokoroModelState.Downloading(before + written, total)
                    }
                }
            }
        } finally {
            abort?.dispose()
        }
        if (written != expected) throw InstallException("The download stopped early. Try again to continue it.")
    }

    private suspend fun unpack(archive: ModelPackage.Archive) {
        val staging = files.staging
        staging.deleteRecursively()
        staging.mkdirs()
        var unpacked = 0L
        _state.value = KokoroModelState.Unpacking(0L, archive.unpackedBytes)
        val input = BufferedInputStream(files.partialArchive.inputStream(), BUFFER_BYTES)
        try {
            TarArchiveInputStream(BZip2CompressorInputStream(input)).use { tar ->
                while (true) {
                    val entry = tar.nextEntry ?: break
                    unpackEntry(tar, entry, staging) { count ->
                        unpacked += count
                        _state.value = KokoroModelState.Unpacking(unpacked, archive.unpackedBytes)
                    }
                }
            }
        } catch (error: IOException) {
            // Past the download, a write failure is almost always a full disk, not the network.
            staging.deleteRecursively()
            throw InstallException("Unpacking failed. Make sure the phone has free space, then try again.", error)
        }
        finishUnpack(staging, archive)
    }

    private suspend fun unpackEntry(
        tar: TarArchiveInputStream,
        entry: TarArchiveEntry,
        staging: File,
        onProgress: (Int) -> Unit,
    ) {
        val target =
            KokoroInstallPlanning.entryTarget(staging, entry.name, builtIn.dirName)
                ?: throw InstallException("The download contained unexpected files.")
        when {
            entry.isDirectory -> target.mkdirs()
            entry.isFile -> {
                target.parentFile?.mkdirs()
                FileOutputStream(target).use { out -> copyWithProgress(tar, out, onProgress) }
            }
            // Links and devices have no place in a model; skip them rather than trust them.
            else -> Unit
        }
    }

    /** Checks the unpacked files, marks them complete, and swaps them in for any older copy. */
    private fun finishUnpack(
        staging: File,
        archive: ModelPackage.Archive,
    ) {
        if (archive.requiredFiles.any { !File(staging, it).isFile }) throw InstallException("The download was incomplete. Try again.")
        File(staging, KokoroModelFiles.INSTALLED_MARKER).writeText(archive.sha256)
        files.dir.deleteRecursively()
        if (!staging.renameTo(files.dir)) throw InstallException("Could not finish installing. Try again.")
    }

    private companion object {
        const val HTTP_PARTIAL = 206

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
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private const val BUFFER_BYTES = 256 * 1024
private const val REPORT_EVERY_BYTES = 2 * 1024 * 1024

/** A message for the user; any other error gets a generic one from [failureText]. */
private class InstallException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

private fun failureText(
    error: Throwable,
    model: BuiltInModel,
): String =
    when (error) {
        is InstallException -> error.message.orEmpty()
        is IOException -> "The download was interrupted. Check the connection and try again to continue it."
        else -> "${model.family} could not be installed. Try again."
    }

/** Copies until the end, checking for cancellation between buffers and reporting progress in steps. */
private suspend fun copyWithProgress(
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
