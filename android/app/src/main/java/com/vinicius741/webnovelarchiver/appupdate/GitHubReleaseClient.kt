package com.vinicius741.webnovelarchiver.appupdate

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Reads the repository's latest GitHub release and downloads its APK. A plain OkHttp stack on
 * purpose: GitHub is not a novel source, so it must not share the Cloudflare-intercepted
 * `NetworkClient` pacing or cookies.
 */
class GitHubReleaseClient(
    private val repository: String,
    private val userAgent: String,
    private val client: OkHttpClient = defaultClient(),
) {
    fun fetchLatestRelease(): LatestRelease {
        val request =
            Request
                .Builder()
                .url("https://api.github.com/repos/$repository/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", userAgent)
                .build()
        val body = client.newCall(request).execute().use { response -> requireSuccess(response).body.string() }
        return GitHubReleaseParsing.parseRelease(body) ?: throw IOException("GitHub returned an unreadable release")
    }

    /** The SHA-256 the download must match: the asset digest, else the release's checksum file. */
    fun expectedChecksum(update: AvailableUpdate): String {
        update.apk.sha256?.let { return it }
        val sums = update.checksumAsset ?: throw IOException("The release has no checksum to verify the download")
        val request =
            Request
                .Builder()
                .url(sums.downloadUrl)
                .header("User-Agent", userAgent)
                .build()
        val text = client.newCall(request).execute().use { response -> requireSuccess(response).body.string() }
        return AppUpdatePlanning.checksumFromSums(text, update.apk.name)
            ?: throw IOException("The release checksum file does not list ${update.apk.name}")
    }

    /**
     * Streams [url] into [target] through a `.part` file, hashing as it goes, and returns the
     * lowercase SHA-256. The target only appears once the whole body has been written.
     */
    fun download(
        scope: CoroutineScope,
        url: String,
        target: File,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): String {
        val partial = File(target.parentFile, "${target.name}.part")
        val digest = MessageDigest.getInstance("SHA-256")
        val request =
            Request
                .Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .build()
        try {
            client.newCall(request).execute().use { response ->
                copyHashing(scope, requireSuccess(response).body, partial, digest, onProgress)
            }
            target.delete()
            if (!partial.renameTo(target)) throw IOException("Could not save the downloaded update")
            return digest.digest().toHex()
        } finally {
            partial.delete()
        }
    }

    private fun copyHashing(
        scope: CoroutineScope,
        body: ResponseBody,
        partial: File,
        digest: MessageDigest,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ) {
        val total = body.contentLength()
        var copied = 0L
        body.byteStream().use { input ->
            partial.outputStream().use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                var read = input.read(buffer)
                while (read >= 0) {
                    scope.ensureActive()
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    copied += read
                    onProgress(copied, total)
                    read = input.read(buffer)
                }
                output.fd.sync()
            }
        }
        if (total > 0 && copied != total) throw IOException("Download ended early")
    }

    private fun requireSuccess(response: Response): Response {
        if (response.isSuccessful) return response
        val message =
            when (response.code) {
                404 -> "No published release was found"
                403, 429 -> "GitHub is limiting update checks; try again later"
                else -> "GitHub returned HTTP ${response.code}"
            }
        throw IOException(message)
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                var read = input.read(buffer)
                while (read >= 0) {
                    digest.update(buffer, 0, read)
                    read = input.read(buffer)
                }
            }
            return digest.digest().toHex()
        }

        fun sha256Bytes(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

        private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

        fun defaultClient(): OkHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
    }
}
