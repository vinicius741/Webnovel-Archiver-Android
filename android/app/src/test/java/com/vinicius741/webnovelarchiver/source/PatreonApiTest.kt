package com.vinicius741.webnovelarchiver.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PatreonApiTest {
    @Test
    fun `parses a posts page with readable and locked posts and the next cursor`() {
        val json =
            """
            {"data":[
              {"id":"171732039","type":"post","attributes":{"title":"Novel - Book 4 Chapter 43: Assembly",
                "published_at":"2026-10-08T07:00:01.000+00:00","current_user_can_view":true,
                "content_json_string":"{\"type\":\"doc\",\"content\":[]}"}},
              {"id":"171672742","type":"post","attributes":{"title":"Novel - Book 4 Chapter 42: Generations",
                "published_at":"2026-10-07T07:00:01.000+00:00","current_user_can_view":false,"content_json_string":null}}
            ],
            "meta":{"pagination":{"total":185,"cursors":{"next":"03:abc"}}}}
            """.trimIndent()

        val page = PatreonApi.parsePostsPage(json)

        assertEquals("03:abc", page.nextCursor)
        assertEquals(listOf("171732039", "171672742"), page.posts.map { it.id })
        val readable = page.posts[0]
        assertTrue(readable.canView)
        assertEquals("""{"type":"doc","content":[]}""", readable.richContent)
        assertEquals(1_791_442_801_000L, readable.publishedAt)
        assertFalse(page.posts[1].canView)
        assertNull(page.posts[1].richContent)
    }

    @Test
    fun `last page has no cursor`() {
        assertNull(PatreonApi.parsePostsPage("""{"data":[],"meta":{"pagination":{"cursors":{"next":null}}}}""").nextCursor)
    }

    @Test
    fun `parses collections`() {
        val json =
            """{"data":[{"id":"2030713","type":"collection","attributes":{"num_posts":185,"title":"The Lich King Reincarnates"}},""" +
                """{"id":"1","type":"collection","attributes":{"title":""}}]}"""

        val collections = PatreonApi.parseCollections(json)

        assertEquals(PatreonCollection("2030713", "The Lich King Reincarnates", 185), collections[0])
        assertEquals(PatreonCollection("1", "Collection 1", null), collections[1])
    }

    @Test
    fun `parses the signed-in account and its paid campaigns`() {
        val json =
            """{"data":{"id":"9","type":"user","attributes":{"full_name":"Reader"}},
               "included":[{"id":"3805883","type":"campaign","attributes":{"name":"A. C. Erinle"}},
                           {"id":"p1","type":"pledge","attributes":{}},
                           {"id":"7","type":"campaign","attributes":{"vanity":"writer"}}]}"""

        val account = PatreonApi.parseCurrentUser(json)

        assertEquals("Reader", account.name)
        assertEquals(listOf(PatreonCampaignRef("3805883", "A. C. Erinle"), PatreonCampaignRef("7", "writer")), account.memberships)
    }

    @Test
    fun `builds encoded API urls`() {
        assertEquals(
            "https://www.patreon.com/api/posts?filter%5Bcollection_id%5D=1&json-api-version=1.0",
            PatreonApi.apiUrl("posts", "filter[collection_id]" to "1"),
        )
    }

    @Test
    fun `session cookie values are normalized and read from cookie headers`() {
        assertEquals("abc123", PatreonSession.normalizePastedValue("  session_id=abc123; Path=/ "))
        assertEquals("abc123", PatreonSession.normalizePastedValue("\"abc123\""))
        assertNull(PatreonSession.normalizePastedValue("   "))
        assertNull(PatreonSession.normalizePastedValue("two words"))
        assertEquals("v", PatreonSession.cookieValue("a=1; session_id=v; b=2"))
        assertNull(PatreonSession.cookieValue("a=1; session_id="))
        assertNull(PatreonSession.cookieValue(null))
    }

    @Test
    fun `rich text becomes chapter html`() {
        val json =
            """
            {"type":"doc","content":[
              {"type":"paragraph","content":[{"type":"text","marks":[{"type":"bold"}],"text":"Book 4 Chapter 43: Assembly"}]},
              {"type":"image","attrs":{"src":"https://c10.patreonusercontent.com/x.png"}},
              {"type":"paragraph","content":[{"type":"text","text":"Liches <alone> & "},{"type":"text","marks":[{"type":"italic"},{"type":"underline"}],"text":"sharks"},
                {"type":"hardBreak"},{"type":"text","text":"Next line"}]},
              {"type":"heading","attrs":{"level":3},"content":[{"type":"text","text":"Interlude"}]},
              {"type":"bulletList","content":[{"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"one"}]}]}]},
              {"type":"mystery","content":[{"type":"text","text":"kept"}]}
            ]}
            """.trimIndent()

        assertEquals(
            "<p><strong>Book 4 Chapter 43: Assembly</strong></p>" +
                "<p>Liches &lt;alone&gt; &amp; <em><u>sharks</u></em><br/>Next line</p>" +
                "<h3>Interlude</h3><ul><li><p>one</p></li></ul>kept",
            PatreonRichText.toHtml(json),
        )
        assertEquals("", PatreonRichText.toHtml("not json"))
    }
}
