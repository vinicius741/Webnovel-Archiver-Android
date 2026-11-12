package com.vinicius741.webnovelarchiver.appupdate

// Pure decisions for in-app update checks against GitHub Releases: version ordering, asset
// selection, and automatic-check timing. Android and network I/O live in AppUpdateCoordinator and
// GitHubReleaseClient.

sealed interface UpdateEvaluation {
    data class UpToDate(
        val latestTag: String,
    ) : UpdateEvaluation

    data class Available(
        val update: AvailableUpdate,
    ) : UpdateEvaluation

    /** A newer release exists but has no APK attached. */
    data class Unusable(
        val latestTag: String,
        val reason: String,
    ) : UpdateEvaluation
}

/**
 * The release track an installed build follows. Stable and beta are separate apps (different
 * application ids), so each only ever offers its own track's releases: a stable release would
 * install beside a beta app instead of updating it, and the reverse.
 */
enum class UpdateChannel(
    /** The `channel=` value in the release-notes marker; stable releases omit it. */
    val markerName: String,
) {
    STABLE("stable"),
    BETA("beta"),
    ;

    /**
     * Whether [release] belongs to this track. Stable takes published, non-prerelease releases
     * (GitHub's "latest" already excludes prereleases) and also refuses a beta marker, in case a beta
     * loses its prerelease flag on GitHub. Beta takes only prereleases whose marker says
     * `channel=beta`, so an unrelated prerelease is never offered to the beta app.
     */
    fun includes(release: LatestRelease): Boolean {
        if (release.draft) return false
        return when (this) {
            STABLE -> !release.prerelease && AppUpdatePlanning.channelFromNotes(release.body) != BETA
            BETA -> release.prerelease && AppUpdatePlanning.channelFromNotes(release.body) == BETA
        }
    }

    /** The newest of this track's releases in a GitHub listing, by marker version code; `null` when none. */
    fun newest(releases: List<LatestRelease>): LatestRelease? =
        releases
            .filter(::includes)
            .maxByOrNull { AppUpdatePlanning.versionCodeFromNotes(it.body) ?: 0 }

    /** The release-notes marker the publish workflow writes for this track. */
    fun marker(versionCode: Int): String =
        when (this) {
            STABLE -> "<!-- app-update versionCode=$versionCode -->"
            BETA -> "<!-- app-update channel=beta versionCode=$versionCode -->"
        }

    companion object {
        /** Reads `BuildConfig.UPDATE_CHANNEL`; anything unknown follows the stable track. */
        fun fromBuildConfig(value: String): UpdateChannel = entries.firstOrNull { it.markerName == value } ?: STABLE
    }
}

object AppUpdatePlanning {
    const val AUTO_CHECK_INTERVAL_MILLIS: Long = 12L * 60 * 60 * 1000
    private const val PREFERRED_APK_PREFIX = "WebnovelArchiver"

    // Stable releases write `<!-- app-update versionCode=N -->`, the exact form that already
    // installed stable apps read; beta releases add `channel=beta` before the version code.
    private val VERSION_CODE_MARKER = Regex("""<!--\s*app-update\s+(?:channel=([A-Za-z]+)\s+)?versionCode=(\d+)\s*-->""")
    private val NUMERIC_VERSION = Regex("""^\d+(?:\.\d+)*""")

    fun versionNameFromTag(tag: String): String = tag.trim().removePrefix("v").removePrefix("V")

    fun versionCodeFromNotes(body: String): Int? =
        VERSION_CODE_MARKER
            .find(body)
            ?.groupValues
            ?.get(2)
            ?.toIntOrNull()

    /** The track a release's marker names: no `channel=` means stable, no marker means none. */
    fun channelFromNotes(body: String): UpdateChannel? {
        val marker = VERSION_CODE_MARKER.find(body) ?: return null
        val name = marker.groupValues[1].ifEmpty { UpdateChannel.STABLE.markerName }
        return UpdateChannel.entries.firstOrNull { it.markerName.equals(name, ignoreCase = true) }
    }

    /**
     * Compares the leading dotted numbers of two version names (`1.0.10-native` > `1.0.9-native`).
     * Suffixes are ignored; a name without a leading number sorts below any numbered one.
     */
    fun compareVersionNames(
        left: String,
        right: String,
    ): Int {
        val a = numericParts(left)
        val b = numericParts(right)
        for (index in 0 until maxOf(a.size, b.size)) {
            val difference = (a.getOrElse(index) { 0L }).compareTo(b.getOrElse(index) { 0L })
            if (difference != 0) return difference
        }
        return 0
    }

    private fun numericParts(version: String): List<Long> =
        NUMERIC_VERSION
            .find(version.trim().removePrefix("v"))
            ?.value
            ?.split('.')
            ?.map { it.toLongOrNull() ?: 0L }
            .orEmpty()

    /** The APK the browser downloads: the workflow's `WebnovelArchiver*.apk` when present, otherwise the only APK. */
    fun selectApk(assets: List<ReleaseAsset>): ReleaseAsset? {
        val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        return apks.firstOrNull { it.name.startsWith(PREFERRED_APK_PREFIX) } ?: apks.singleOrNull()
    }

    fun evaluate(
        release: LatestRelease,
        installedVersionCode: Long,
        installedVersionName: String,
        channel: UpdateChannel = UpdateChannel.STABLE,
    ): UpdateEvaluation {
        if (!channel.includes(release)) return UpdateEvaluation.UpToDate(release.tag)
        val versionName = versionNameFromTag(release.tag)
        val versionCode = versionCodeFromNotes(release.body)
        val newer =
            if (versionCode != null) {
                versionCode > installedVersionCode
            } else {
                compareVersionNames(versionName, installedVersionName) > 0
            }
        if (!newer) return UpdateEvaluation.UpToDate(release.tag)
        val apk =
            selectApk(release.assets)
                ?: return UpdateEvaluation.Unusable(release.tag, "Release ${release.tag} has no app installer attached")
        return UpdateEvaluation.Available(
            AvailableUpdate(
                tag = release.tag,
                versionName = versionName,
                versionCode = versionCode,
                apk = apk,
                releaseUrl = release.htmlUrl,
            ),
        )
    }

    fun shouldAutoCheck(
        enabled: Boolean,
        nowMillis: Long,
        lastCheckedAtMillis: Long,
        intervalMillis: Long = AUTO_CHECK_INTERVAL_MILLIS,
    ): Boolean {
        if (!enabled) return false
        if (lastCheckedAtMillis <= 0L || nowMillis < lastCheckedAtMillis) return true
        return nowMillis - lastCheckedAtMillis >= intervalMillis
    }

    /** The startup dialog appears once per release and never for a release the user skipped. */
    fun shouldPrompt(
        updateTag: String,
        promptedTag: String?,
        skippedTag: String?,
    ): Boolean = updateTag != promptedTag && updateTag != skippedTag
}
