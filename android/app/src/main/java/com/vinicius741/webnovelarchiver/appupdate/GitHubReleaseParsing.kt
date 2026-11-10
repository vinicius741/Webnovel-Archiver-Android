package com.vinicius741.webnovelarchiver.appupdate

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val size: Long,
)

data class LatestRelease(
    val tag: String,
    val title: String,
    val body: String,
    val htmlUrl: String,
    val publishedAt: String?,
    val draft: Boolean,
    val prerelease: Boolean,
    val assets: List<ReleaseAsset>,
)

data class AvailableUpdate(
    val tag: String,
    val versionName: String,
    /** Declared by the release workflow's marker; `null` for releases published before it existed. */
    val versionCode: Int?,
    val apk: ReleaseAsset,
    val releaseUrl: String,
)

/** Reads GitHub's release JSON through Gson's tree API, so R8 shrinking cannot break field mapping. */
object GitHubReleaseParsing {
    /** Parses GitHub's `GET /repos/{owner}/{repo}/releases/latest` body; `null` when it is not a release. */
    fun parseRelease(json: String): LatestRelease? {
        val root = runCatching { JsonParser.parseString(json) }.getOrNull()
        if (root == null || !root.isJsonObject) return null
        val release = root.asJsonObject
        val tag = release.string("tag_name")?.takeIf { it.isNotBlank() } ?: return null
        val assets =
            release
                .array("assets")
                .mapNotNull { element -> element.takeIf { it.isJsonObject }?.asJsonObject?.let(::parseAsset) }
        return LatestRelease(
            tag = tag,
            title = release.string("name")?.takeIf { it.isNotBlank() } ?: tag,
            body = release.string("body").orEmpty(),
            htmlUrl = release.string("html_url").orEmpty(),
            publishedAt = release.string("published_at"),
            draft = release.boolean("draft"),
            prerelease = release.boolean("prerelease"),
            assets = assets,
        )
    }

    private fun parseAsset(asset: JsonObject): ReleaseAsset? {
        val name = asset.string("name")?.takeIf { it.isNotBlank() } ?: return null
        val url = asset.string("browser_download_url")?.takeIf { it.startsWith("https://") } ?: return null
        return ReleaseAsset(name = name, downloadUrl = url, size = asset.long("size"))
    }

    /** Re-serializes only the fields [parseRelease] reads, so the cached copy stays small. */
    fun serializeRelease(release: LatestRelease): String {
        val json = JsonObject()
        json.addProperty("tag_name", release.tag)
        json.addProperty("name", release.title)
        json.addProperty(
            "body",
            AppUpdatePlanning.versionCodeFromNotes(release.body)?.let { "<!-- app-update versionCode=$it -->" }.orEmpty(),
        )
        json.addProperty("html_url", release.htmlUrl)
        json.addProperty("published_at", release.publishedAt)
        json.addProperty("draft", release.draft)
        json.addProperty("prerelease", release.prerelease)
        val assets = JsonArray()
        release.assets.forEach { asset ->
            assets.add(
                JsonObject().apply {
                    addProperty("name", asset.name)
                    addProperty("browser_download_url", asset.downloadUrl)
                    addProperty("size", asset.size)
                },
            )
        }
        json.add("assets", assets)
        return json.toString()
    }

    private fun JsonObject.string(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.long(name: String): Long =
        get(name)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asLong }.getOrNull() } ?: 0L

    private fun JsonObject.boolean(name: String): Boolean =
        get(name)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asBoolean }.getOrNull() } ?: false

    private fun JsonObject.array(name: String): List<JsonElement> =
        get(name)
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray
            ?.toList()
            .orEmpty()
}
