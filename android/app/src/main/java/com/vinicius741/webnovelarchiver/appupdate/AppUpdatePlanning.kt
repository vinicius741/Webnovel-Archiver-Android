package com.vinicius741.webnovelarchiver.appupdate

// Pure decisions for in-app updates from GitHub Releases: version ordering, asset/checksum
// selection, and automatic-check timing. Android, network, and file I/O live in
// AppUpdateCoordinator, GitHubReleaseClient, and AppUpdateInstaller.

sealed interface UpdateEvaluation {
    data class UpToDate(
        val latestTag: String,
    ) : UpdateEvaluation

    data class Available(
        val update: AvailableUpdate,
    ) : UpdateEvaluation

    /** A newer release exists but has nothing this app can install. */
    data class Unusable(
        val latestTag: String,
        val reason: String,
    ) : UpdateEvaluation
}

object AppUpdatePlanning {
    const val AUTO_CHECK_INTERVAL_MILLIS: Long = 12L * 60 * 60 * 1000
    const val CHECKSUM_FILE_NAME = "SHA256SUMS.txt"
    private const val PREFERRED_APK_PREFIX = "WebnovelArchiver"
    private val VERSION_CODE_MARKER = Regex("""<!--\s*app-update\s+versionCode=(\d+)\s*-->""")
    private val NUMERIC_VERSION = Regex("""^\d+(?:\.\d+)*""")
    internal val SHA256_HEX = Regex("^[0-9a-f]{64}$")

    fun versionNameFromTag(tag: String): String = tag.trim().removePrefix("v").removePrefix("V")

    fun versionCodeFromNotes(body: String): Int? =
        VERSION_CODE_MARKER
            .find(body)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()

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

    /** The installable APK: the workflow's `WebnovelArchiver*.apk` when present, otherwise the only APK. */
    fun selectApk(assets: List<ReleaseAsset>): ReleaseAsset? {
        val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        return apks.firstOrNull { it.name.startsWith(PREFERRED_APK_PREFIX) } ?: apks.singleOrNull()
    }

    fun evaluate(
        release: LatestRelease,
        installedVersionCode: Long,
        installedVersionName: String,
    ): UpdateEvaluation {
        if (release.draft || release.prerelease) return UpdateEvaluation.UpToDate(release.tag)
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
                checksumAsset = release.assets.firstOrNull { it.name.equals(CHECKSUM_FILE_NAME, ignoreCase = true) },
                releaseUrl = release.htmlUrl,
            ),
        )
    }

    /** Finds [fileName]'s hash in `sha256sum` output (`<hex>  <name>` or `<hex> *<name>`). */
    fun checksumFromSums(
        sums: String,
        fileName: String,
    ): String? =
        sums.lineSequence().firstNotNullOfOrNull { line ->
            val parts = line.trim().split(Regex("\\s+"), limit = 2)
            val hash = parts.getOrNull(0)?.lowercase()
            val name = parts.getOrNull(1)?.removePrefix("*")
            hash?.takeIf { name == fileName && SHA256_HEX.matches(it) }
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

    /** Why a downloaded APK must not be handed to the installer, or `null` when it may proceed. */
    fun archiveRejection(
        archivePackage: String?,
        archiveVersionCode: Long?,
        installedPackage: String,
        installedVersionCode: Long,
        signaturesMatch: Boolean,
    ): String? =
        when {
            archivePackage == null || archiveVersionCode == null -> "The downloaded file is not a valid app"
            archivePackage != installedPackage -> "The downloaded app is a different app ($archivePackage)"
            archiveVersionCode <= installedVersionCode -> "The downloaded app is not newer than the installed one"
            !signaturesMatch -> "The downloaded app is not signed with this app's release key"
            else -> null
        }
}
