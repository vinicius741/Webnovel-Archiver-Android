package com.vinicius741.webnovelarchiver.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

class SourceRegistryTest {
    @Test
    fun descriptorsHaveUniqueStableIdsAndOwnedHosts() {
        val providers = SourceRegistry.all()

        assertEquals(providers.size, providers.map { it.id }.distinct().size)
        providers.forEach { provider ->
            assertTrue(provider.id.matches(Regex("[a-z][a-z0-9_]*")))
            assertTrue(provider.descriptor.hosts.isNotEmpty())
            assertSame(provider, SourceRegistry.getById(provider.id))
            assertSame(provider, SourceRegistry.providerForHost(requireNotNull(URI(provider.baseUrl).host)))
        }
    }

    @Test
    fun resolverReturnsTypedNormalizedMatches() {
        val cases =
            listOf(
                Triple("https://www.royalroad.com/fiction/123/title", "royal_road", SourceUrlKind.STORY),
                Triple("https://www.royalroad.com/fiction/123/chapter/456/title", "royal_road", SourceUrlKind.CHAPTER),
                Triple("https://www.scribblehub.com/series/99/title/", "scribble_hub", SourceUrlKind.STORY),
                Triple("https://forums.spacebattles.com/threads/title.123/", "space_battles", SourceUrlKind.STORY),
                Triple("https://forums.spacebattles.com/posts/456/", "space_battles", SourceUrlKind.CHAPTER),
                Triple("https://m.fanfiction.net/s/7347955/8/title?ref=x", "fanfiction_net", SourceUrlKind.STORY),
            )

        cases.forEach { (url, sourceId, kind) ->
            val match = requireNotNull(SourceRegistry.resolve(url))
            assertEquals(sourceId, match.provider.id)
            assertEquals(kind, match.kind)
        }
        assertEquals(
            "https://www.fanfiction.net/s/7347955/8/title",
            requireNotNull(SourceRegistry.resolve(cases.last().first)).normalizedUrl,
        )
    }

    @Test
    fun resolverRejectsSupportedUrlEmbeddedInAnUnownedHost() {
        val disguised =
            "https://example.com/import?next=" +
                "https://www.royalroad.com/fiction/123/title"

        assertNull(SourceRegistry.resolve(disguised))
        assertFalse(SourceUrlValidation.isImportableStoryUrl(disguised))
        assertNull(
            SourceRegistry.resolve(
                "https://www.royalroad.com/home?next=https://www.royalroad.com/fiction/123/title",
            ),
        )
    }

    @Test
    fun legacyDisplaySettingKeysResolveToStableIds() {
        assertEquals("royal_road", SourceRegistry.sourceIdForPersistedKey("RoyalRoad"))
        assertEquals("scribble_hub", SourceRegistry.sourceIdForPersistedKey("Scribble Hub"))
        assertEquals("space_battles", SourceRegistry.sourceIdForPersistedKey("space_battles"))
        assertNull(SourceRegistry.sourceIdForPersistedKey("future_source"))
    }
}
