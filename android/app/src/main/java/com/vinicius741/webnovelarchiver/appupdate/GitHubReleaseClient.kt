package com.vinicius741.webnovelarchiver.appupdate

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Reads the newest GitHub release of the installed build's [channel]. A plain OkHttp stack on
 * purpose: GitHub is not a novel source, so it must not share the Cloudflare-intercepted
 * `NetworkClient` pacing or cookies.
 *
 * Stable reads `releases/latest`, which GitHub never points at a prerelease. Beta releases are
 * published as prereleases, so the beta app reads the release listing and picks the release marked
 * `channel=beta` with the highest version code. It reads every page (up to a bound) because GitHub
 * orders the listing by each release's commit date, so a beta built later from an older commit can
 * sit on a later page than an earlier beta.
 */
class GitHubReleaseClient(
    private val repository: String,
    private val userAgent: String,
    private val channel: UpdateChannel = UpdateChannel.STABLE,
    private val baseUrl: String = "https://api.github.com",
    private val client: OkHttpClient = defaultClient(),
) {
    /** The newest release of [channel], or `null` when the beta track has no release yet. */
    fun fetchLatestRelease(): LatestRelease? =
        when (channel) {
            UpdateChannel.STABLE ->
                GitHubReleaseParsing.parseRelease(get("releases/latest"))
                    ?: throw IOException("GitHub returned an unreadable release")
            UpdateChannel.BETA -> fetchNewestBeta()
        }

    private fun fetchNewestBeta(): LatestRelease? {
        val betas = mutableListOf<LatestRelease>()
        for (page in 1..MAX_LISTING_PAGES) {
            val releases =
                GitHubReleaseParsing.parseReleaseList(get("releases?per_page=$LISTING_PAGE_SIZE&page=$page"))
                    ?: throw IOException("GitHub returned an unreadable release list")
            betas += releases.filter(channel::includes)
            if (releases.size < LISTING_PAGE_SIZE) break
        }
        return channel.newest(betas)
    }

    private fun get(path: String): String {
        val request =
            Request
                .Builder()
                .url("${baseUrl.trimEnd('/')}/repos/$repository/$path")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", userAgent)
                .build()
        return client.newCall(request).execute().use { response -> requireSuccess(response).body.string() }
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
        /** GitHub's largest page; releases are listed newest first. */
        private const val LISTING_PAGE_SIZE = 100

        /** Bounds the beta search (500 releases) and its use of the unauthenticated rate limit. */
        private const val MAX_LISTING_PAGES = 5

        fun defaultClient(): OkHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
    }
}
