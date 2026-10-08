package com.vinicius741.webnovelarchiver.source

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.vinicius741.webnovelarchiver.source.network.NetworkClient
import java.net.URLEncoder
import java.time.OffsetDateTime

data class PatreonAccount(
    val name: String?,
    val memberships: List<PatreonCampaignRef>,
)

data class PatreonCampaignRef(
    val id: String,
    val name: String,
)

data class PatreonCollection(
    val id: String,
    val title: String,
    val postCount: Int?,
)

data class PatreonPost(
    val id: String,
    val title: String,
    val publishedAt: Long?,
    val canView: Boolean,
    /** Patreon's rich-text document (`content_json_string`); null when locked for this session. */
    val richContent: String?,
)

data class PatreonPostsPage(
    val posts: List<PatreonPost>,
    val nextCursor: String?,
)

/**
 * Reads the patreon.com web API with the session cookie the shared cookie store already holds (see
 * [com.vinicius741.webnovelarchiver.source.network.AndroidCookieJar]); signed out, the same
 * endpoints return public data and locked posts. Patreon is not a novel source: it supplements a
 * source story's chapter list, so it lives beside [PatreonStatsFetcher] rather than in the registry.
 */
class PatreonApi internal constructor(
    private val fetchPage: suspend (String) -> String,
) {
    constructor(network: NetworkClient) : this(
        fetchPage = { url -> network.fetch(url, PATREON_CALL_TIMEOUT_MILLIS, maximumAttemptsOverride = 2) },
    )

    suspend fun currentUser(): PatreonAccount = parseCurrentUser(fetchPage(apiUrl("current_user", "include" to "pledges.campaign")))

    suspend fun campaignId(creatorUrl: String): String? =
        PatreonStatsFetcher.extractCampaignId(fetchPage(PatreonStatsFetcher.aboutUrl(creatorUrl)))

    suspend fun collections(campaignId: String): List<PatreonCollection> =
        parseCollections(
            fetchPage(
                apiUrl(
                    "collection",
                    "filter[campaign_id]" to campaignId,
                    "filter[must_contain_at_least_one_published_post]" to "true",
                    "fields[collection]" to "title,num_posts",
                    "page[count]" to "100",
                ),
            ),
        )

    /** One newest-first page of a collection's published posts, including readable bodies. */
    suspend fun collectionPosts(
        campaignId: String,
        collectionId: String,
        cursor: String?,
    ): PatreonPostsPage {
        val params =
            buildList {
                add("filter[campaign_id]" to campaignId)
                add("filter[collection_id]" to collectionId)
                add("filter[contains_exclusive_posts]" to "true")
                add("filter[is_draft]" to "false")
                add("sort" to "-published_at")
                add("fields[post]" to "title,published_at,current_user_can_view,content_json_string")
                add("page[count]" to POSTS_PAGE_SIZE.toString())
                cursor?.let { add("page[cursor]" to it) }
            }
        return parsePostsPage(fetchPage(apiUrl("posts", *params.toTypedArray())))
    }

    companion object {
        private const val PATREON_CALL_TIMEOUT_MILLIS = 30_000L
        const val POSTS_PAGE_SIZE = 20

        internal fun apiUrl(
            path: String,
            vararg params: Pair<String, String>,
        ): String =
            "https://www.patreon.com/api/$path?" +
                (params.toList() + ("json-api-version" to "1.0")).joinToString("&") { (key, value) ->
                    "${encode(key)}=${encode(value)}"
                }

        private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

        internal fun parseCurrentUser(json: String): PatreonAccount {
            val root = JsonParser.parseString(json).asJsonObject
            val name =
                root
                    .getAsJsonObject("data")
                    ?.getAsJsonObject("attributes")
                    ?.string("full_name")
            val memberships =
                root.included("campaign").map { campaign ->
                    val attrs = campaign.getAsJsonObject("attributes")
                    PatreonCampaignRef(
                        id = campaign.string("id").orEmpty(),
                        name = attrs?.string("name") ?: attrs?.string("vanity") ?: "Campaign ${campaign.string("id")}",
                    )
                }
            return PatreonAccount(name, memberships)
        }

        internal fun parseCollections(json: String): List<PatreonCollection> =
            JsonParser
                .parseString(json)
                .asJsonObject
                .getAsJsonArray("data")
                ?.mapNotNull { element ->
                    val collection = element.asJsonObject
                    val id = collection.string("id") ?: return@mapNotNull null
                    val attrs = collection.getAsJsonObject("attributes")
                    PatreonCollection(
                        id = id,
                        title = attrs?.string("title").orEmpty().ifBlank { "Collection $id" },
                        postCount = attrs?.get("num_posts")?.takeUnless(JsonElement::isJsonNull)?.asInt,
                    )
                }.orEmpty()

        internal fun parsePostsPage(json: String): PatreonPostsPage {
            val root = JsonParser.parseString(json).asJsonObject
            val posts =
                root
                    .getAsJsonArray("data")
                    ?.mapNotNull { element ->
                        val post = element.asJsonObject
                        val id = post.string("id") ?: return@mapNotNull null
                        val attrs = post.getAsJsonObject("attributes") ?: return@mapNotNull null
                        val canView = attrs.get("current_user_can_view")?.takeUnless(JsonElement::isJsonNull)?.asBoolean == true
                        PatreonPost(
                            id = id,
                            title = attrs.string("title").orEmpty(),
                            publishedAt =
                                attrs
                                    .string(
                                        "published_at",
                                    )?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() },
                            canView = canView,
                            richContent = attrs.string("content_json_string")?.takeIf { canView && it.isNotBlank() },
                        )
                    }.orEmpty()
            val nextCursor =
                root
                    .getAsJsonObject("meta")
                    ?.getAsJsonObject("pagination")
                    ?.getAsJsonObject("cursors")
                    ?.string("next")
            return PatreonPostsPage(posts, nextCursor?.takeIf { it.isNotBlank() })
        }

        private fun JsonObject.included(type: String): List<JsonObject> =
            getAsJsonArray("included")
                ?.map { it.asJsonObject }
                ?.filter { it.string("type") == type }
                .orEmpty()

        private fun JsonObject.string(key: String): String? = get(key)?.takeUnless(JsonElement::isJsonNull)?.asString
    }
}
