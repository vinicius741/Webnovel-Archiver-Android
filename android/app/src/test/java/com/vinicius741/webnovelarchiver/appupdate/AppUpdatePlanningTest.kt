package com.vinicius741.webnovelarchiver.appupdate

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdatePlanningTest {
    private val apkHash = "2e6dd3ce2d41ff58cfc424ae1799d7536608e42d954d6270c7fe353503ee6ad4"

    private fun releaseJson(
        tag: String = "v1.0.4-native",
        body: String = "Notes\n\n<!-- app-update versionCode=5 -->\n",
        draft: Boolean = false,
        prerelease: Boolean = false,
        assets: String =
            """
            [
              {"name":"SHA256SUMS.txt","size":87,"browser_download_url":"https://github.com/o/r/releases/download/$tag/SHA256SUMS.txt"},
              {"name":"WebnovelArchiver-v1.0.4-native-1-1.apk","size":2065029,
               "browser_download_url":"https://github.com/o/r/releases/download/$tag/WebnovelArchiver-v1.0.4-native-1-1.apk",
               "digest":"sha256:$apkHash"}
            ]
            """.trimIndent(),
    ): String =
        """
        {"tag_name":"$tag","name":"Webnovel Archiver","body":${com.google.gson.JsonPrimitive(body)},
         "html_url":"https://github.com/o/r/releases/tag/$tag","published_at":"2026-11-05T18:00:54Z",
         "draft":$draft,"prerelease":$prerelease,"assets":$assets}
        """.trimIndent()

    private fun parse(json: String) = requireNotNull(GitHubReleaseParsing.parseRelease(json))

    @Test
    fun `parses release assets`() {
        val release = parse(releaseJson())
        assertEquals("v1.0.4-native", release.tag)
        assertEquals(2, release.assets.size)
        assertEquals(2065029L, release.assets[1].size)
        assertTrue(release.assets[1].downloadUrl.endsWith(".apk"))
    }

    @Test
    fun `rejects non release bodies`() {
        assertNull(GitHubReleaseParsing.parseRelease("""{"message":"Not Found"}"""))
        assertNull(GitHubReleaseParsing.parseRelease("not json"))
        assertNull(GitHubReleaseParsing.parseRelease("[]"))
    }

    @Test
    fun `ignores assets without an https download url`() {
        val release = parse(releaseJson(assets = """[{"name":"x.apk","browser_download_url":"http://example.com/x.apk"}]"""))
        assertTrue(release.assets.isEmpty())
    }

    @Test
    fun `version code marker decides when present`() {
        val release = parse(releaseJson(body = "<!-- app-update versionCode=5 -->"))
        assertTrue(AppUpdatePlanning.evaluate(release, 4, "9.9.9") is UpdateEvaluation.Available)
        assertTrue(AppUpdatePlanning.evaluate(release, 5, "0.0.1") is UpdateEvaluation.UpToDate)
    }

    @Test
    fun `falls back to version names without a marker`() {
        val release = parse(releaseJson(body = "Download the APK"))
        val available = AppUpdatePlanning.evaluate(release, 100, "1.0.3-native")
        assertTrue(available is UpdateEvaluation.Available)
        assertNull((available as UpdateEvaluation.Available).update.versionCode)
        assertEquals("1.0.4-native", available.update.versionName)
        assertTrue(AppUpdatePlanning.evaluate(release, 1, "1.0.4-native") is UpdateEvaluation.UpToDate)
    }

    @Test
    fun `drafts and prereleases are never offered`() {
        assertTrue(AppUpdatePlanning.evaluate(parse(releaseJson(draft = true)), 1, "1.0.0") is UpdateEvaluation.UpToDate)
        assertTrue(AppUpdatePlanning.evaluate(parse(releaseJson(prerelease = true)), 1, "1.0.0") is UpdateEvaluation.UpToDate)
    }

    @Test
    fun `newer release without an apk is unusable`() {
        val release = parse(releaseJson(assets = "[]"))
        assertTrue(AppUpdatePlanning.evaluate(release, 1, "1.0.0") is UpdateEvaluation.Unusable)
    }

    @Test
    fun `compares dotted versions numerically and ignores suffixes`() {
        assertTrue(AppUpdatePlanning.compareVersionNames("1.0.10-native", "1.0.9-native") > 0)
        assertTrue(AppUpdatePlanning.compareVersionNames("v2.0", "1.9.9") > 0)
        assertEquals(0, AppUpdatePlanning.compareVersionNames("1.0", "1.0.0-native"))
        assertTrue(AppUpdatePlanning.compareVersionNames("native", "0.0.1") < 0)
    }

    @Test
    fun `selects the workflow apk over other apks`() {
        val assets =
            listOf(
                ReleaseAsset("other.apk", "https://x/other.apk", 1),
                ReleaseAsset("WebnovelArchiver.apk", "https://x/w.apk", 1),
            )
        assertEquals("WebnovelArchiver.apk", AppUpdatePlanning.selectApk(assets)?.name)
        assertNull(AppUpdatePlanning.selectApk(assets.take(1) + ReleaseAsset("b.apk", "https://x/b.apk", 1)))
        assertEquals("other.apk", AppUpdatePlanning.selectApk(assets.take(1))?.name)
    }

    @Test
    fun `automatic checks respect the toggle and interval`() {
        val interval = AppUpdatePlanning.AUTO_CHECK_INTERVAL_MILLIS
        assertFalse(AppUpdatePlanning.shouldAutoCheck(false, 10_000, 0))
        assertTrue(AppUpdatePlanning.shouldAutoCheck(true, 10_000, 0))
        assertFalse(AppUpdatePlanning.shouldAutoCheck(true, 1_000 + interval - 1, 1_000))
        assertTrue(AppUpdatePlanning.shouldAutoCheck(true, 1_000 + interval, 1_000))
        // A clock moved backwards must not suppress checks until it catches up.
        assertTrue(AppUpdatePlanning.shouldAutoCheck(true, 500, 1_000))
    }

    @Test
    fun `prompts once per release and never for a skipped one`() {
        assertTrue(AppUpdatePlanning.shouldPrompt("v2", promptedTag = "v1", skippedTag = null))
        assertFalse(AppUpdatePlanning.shouldPrompt("v2", promptedTag = "v2", skippedTag = null))
        assertFalse(AppUpdatePlanning.shouldPrompt("v2", promptedTag = null, skippedTag = "v2"))
    }

    private val betaBody = "Beta notes\n\n<!-- app-update channel=beta versionCode=1000007 -->\n"

    @Test
    fun `reads the channel from the release marker`() {
        assertEquals(UpdateChannel.STABLE, AppUpdatePlanning.channelFromNotes("<!-- app-update versionCode=5 -->"))
        assertEquals(UpdateChannel.BETA, AppUpdatePlanning.channelFromNotes(betaBody))
        assertEquals(1000007, AppUpdatePlanning.versionCodeFromNotes(betaBody))
        assertNull(AppUpdatePlanning.channelFromNotes("Download the APK"))
        assertNull(AppUpdatePlanning.channelFromNotes("<!-- app-update channel=nightly versionCode=5 -->"))
        assertEquals(UpdateChannel.BETA, UpdateChannel.fromBuildConfig("beta"))
        assertEquals(UpdateChannel.STABLE, UpdateChannel.fromBuildConfig("anything else"))
    }

    @Test
    fun `stable and beta apps only take their own track`() {
        val beta = parse(releaseJson(tag = "v1.0.21-native-beta.7", body = betaBody, prerelease = true))
        val stable = parse(releaseJson())
        val unmarkedPrerelease = parse(releaseJson(prerelease = true))
        assertTrue(AppUpdatePlanning.evaluate(beta, 1000006, "x", UpdateChannel.BETA) is UpdateEvaluation.Available)
        assertTrue(AppUpdatePlanning.evaluate(beta, 1000007, "x", UpdateChannel.BETA) is UpdateEvaluation.UpToDate)
        assertTrue(AppUpdatePlanning.evaluate(beta, 1, "1.0.0", UpdateChannel.STABLE) is UpdateEvaluation.UpToDate)
        assertTrue(AppUpdatePlanning.evaluate(stable, 1, "1.0.0", UpdateChannel.BETA) is UpdateEvaluation.UpToDate)
        assertTrue(AppUpdatePlanning.evaluate(unmarkedPrerelease, 1, "1.0.0", UpdateChannel.BETA) is UpdateEvaluation.UpToDate)
        // A beta that lost its prerelease flag belongs to neither track: betas are always published
        // as prereleases, and the stable app must never offer a beta APK.
        val unflaggedBeta = parse(releaseJson(body = betaBody))
        assertFalse(UpdateChannel.BETA.includes(unflaggedBeta))
        assertFalse(UpdateChannel.STABLE.includes(unflaggedBeta))
        assertTrue(AppUpdatePlanning.evaluate(unflaggedBeta, 1, "1.0.0", UpdateChannel.STABLE) is UpdateEvaluation.UpToDate)
    }

    @Test
    fun `picks the newest beta from a release listing`() {
        val listing =
            "[" +
                listOf(
                    releaseJson(tag = "v1.0.22-native"),
                    releaseJson(
                        tag = "v1.0.21-native-beta.9",
                        body = "<!-- app-update channel=beta versionCode=1000009 -->",
                        prerelease = true,
                    ),
                    releaseJson(
                        tag = "v1.0.21-native-beta.12",
                        body = "<!-- app-update channel=beta versionCode=1000012 -->",
                        draft = true,
                        prerelease = true,
                    ),
                    releaseJson(tag = "v1.0.21-native-beta.7", body = betaBody, prerelease = true),
                ).joinToString(",") + "]"
        val releases = requireNotNull(GitHubReleaseParsing.parseReleaseList(listing))
        assertEquals(4, releases.size)
        assertEquals("v1.0.21-native-beta.9", UpdateChannel.BETA.newest(releases)?.tag)
        assertNull(UpdateChannel.BETA.newest(releases.take(1)))
        assertNull(GitHubReleaseParsing.parseReleaseList(releaseJson()))
    }

    @Test
    fun `cached copy keeps the beta marker`() {
        val beta = parse(releaseJson(tag = "v1.0.21-native-beta.7", body = betaBody, prerelease = true))
        val cached = parse(GitHubReleaseParsing.serializeRelease(beta))
        assertEquals("<!-- app-update channel=beta versionCode=1000007 -->", cached.body)
        assertTrue(UpdateChannel.BETA.includes(cached))
        val stable = parse(GitHubReleaseParsing.serializeRelease(parse(releaseJson())))
        assertEquals("<!-- app-update versionCode=5 -->", stable.body)
    }

    @Test
    fun `beta client pages past a long run of stable releases`() {
        val server = MockWebServer()
        server.start()
        try {
            val stablePage = "[" + List(100) { releaseJson(tag = "v1.0.$it-native") }.joinToString(",") + "]"
            server.enqueue(MockResponse().setBody(stablePage))
            server.enqueue(
                MockResponse().setBody(
                    "[" + releaseJson(tag = "v1.0.21-native-beta.7", body = betaBody, prerelease = true) + "]",
                ),
            )
            val beta = GitHubReleaseClient("o/r", "test", UpdateChannel.BETA, server.url("/").toString()).fetchLatestRelease()
            assertEquals("v1.0.21-native-beta.7", beta?.tag)
            assertEquals("/repos/o/r/releases?per_page=100&page=1", server.takeRequest().path)
            assertEquals("/repos/o/r/releases?per_page=100&page=2", server.takeRequest().path)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `beta client finds the newest beta on any page and none when there is no beta`() {
        val server = MockWebServer()
        server.start()
        try {
            // GitHub orders by commit date: a later beta built from an older commit can come after an earlier beta.
            val earlierBeta =
                releaseJson(tag = "v1.0.22-native-beta.8", body = "<!-- app-update channel=beta versionCode=1000008 -->", prerelease = true)
            val firstPage = "[" + (List(99) { releaseJson(tag = "v1.0.$it-native") } + earlierBeta).joinToString(",") + "]"
            server.enqueue(MockResponse().setBody(firstPage))
            server.enqueue(
                MockResponse().setBody(
                    "[" +
                        releaseJson(
                            tag = "v1.0.21-native-beta.9",
                            body = "<!-- app-update channel=beta versionCode=1000009 -->",
                            prerelease = true,
                        ) +
                        "]",
                ),
            )
            server.enqueue(MockResponse().setBody("[" + releaseJson() + "]"))
            val client = GitHubReleaseClient("o/r", "test", UpdateChannel.BETA, server.url("/").toString())
            assertEquals("v1.0.21-native-beta.9", client.fetchLatestRelease()?.tag)
            assertNull(client.fetchLatestRelease())
            assertEquals(3, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `beta client reads the release listing and stable reads latest`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse().setBody(
                    "[" + releaseJson(tag = "v1.0.21-native-beta.7", body = betaBody, prerelease = true) + "]",
                ),
            )
            server.enqueue(MockResponse().setBody(releaseJson()))
            val base = server.url("/").toString()
            val beta = GitHubReleaseClient("o/r", "test", UpdateChannel.BETA, base).fetchLatestRelease()
            assertEquals("v1.0.21-native-beta.7", beta?.tag)
            assertEquals("/repos/o/r/releases?per_page=100&page=1", server.takeRequest().path)
            val stable = GitHubReleaseClient("o/r", "test", UpdateChannel.STABLE, base).fetchLatestRelease()
            assertEquals("v1.0.4-native", stable?.tag)
            assertEquals("/repos/o/r/releases/latest", server.takeRequest().path)
        } finally {
            server.shutdown()
        }
    }
}
