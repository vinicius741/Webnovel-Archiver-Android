package com.vinicius741.webnovelarchiver.simulation

import com.google.gson.JsonParser
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.Reader

/** Explicit sanitized recordings. A missing route never initiates a recording or a live request. */
internal class SimulationReplay(
    manifest: Reader,
    readAsset: (String) -> ByteArray,
) {
    data class Payload(
        val bytes: ByteArray,
        val contentType: String,
    )

    private data class Route(
        val method: String,
        val url: String,
        val form: Map<String, String>,
        val payload: Payload,
    )

    private val root = JsonParser.parseReader(manifest).asJsonObject
    val storyUrls: List<String> = root.getAsJsonArray("storyUrls").map { it.asString }
    private val routes =
        root.getAsJsonArray("routes").map { element ->
            val route = element.asJsonObject
            val asset = route.get("asset").asString
            require(asset.startsWith("simulation/replay/") && ".." !in asset && '\\' !in asset)
            Route(
                route.get("method")?.asString ?: "GET",
                normalizedUrl(route.get("url").asString),
                route
                    .getAsJsonObject("form")
                    ?.entrySet()
                    ?.associate { it.key to it.value.asString }
                    .orEmpty(),
                Payload(readAsset(asset), route.get("contentType")?.asString ?: "text/html"),
            )
        }

    init {
        require(root.get("version").asInt == 1)
    }

    fun match(request: Request): Payload? {
        val form = (request.body as? FormBody)?.let { body -> (0 until body.size).associate { body.name(it) to body.value(it) } }.orEmpty()
        return routes
            .singleOrNull {
                it.method == request.method && it.url ==
                    normalizedUrl(
                        request.url.toString(),
                    ) && it.form == form
            }?.payload
    }

    private fun normalizedUrl(raw: String): String {
        val url = raw.toHttpUrl()
        val query =
            (0 until url.querySize)
                .map {
                    url.queryParameterName(it) to url.queryParameterValue(it)
                }.sortedWith(compareBy({ it.first }, { it.second }))
        return url
            .newBuilder()
            .fragment(null)
            .query(null)
            .apply {
                query.forEach { (name, value) ->
                    addQueryParameter(name, value)
                }
            }.build()
            .toString()
    }
}
