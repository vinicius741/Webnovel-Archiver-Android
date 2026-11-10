package com.vinicius741.webnovelarchiver.appupdate

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Reads the repository's latest GitHub release. A plain OkHttp stack on
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
        fun defaultClient(): OkHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
    }
}
