package com.vinicius741.webnovelarchiver.appupdate

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
}
