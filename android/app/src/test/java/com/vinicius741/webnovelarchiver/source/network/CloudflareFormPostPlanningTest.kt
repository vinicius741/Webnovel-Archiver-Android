package com.vinicius741.webnovelarchiver.source.network

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudflareFormPostPlanningTest {
    @Test
    fun fetchKeepsTheAjaxHeaderAndEncodedFormBody() {
        val request =
            CloudflareWebViewRequest(
                url = "https://www.scribblehub.com/wp-admin/admin-ajax.php",
                method = "POST",
                userAgent = "test",
                headers =
                    mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Content-Type" to "application/x-www-form-urlencoded",
                        "X-CSRF-Token" to "test-token",
                        "Origin" to "https://invalid.example",
                    ),
                postData = "action=wi_getreleases_pagination&pagenum=2".toByteArray(),
            )

        val script = CloudflareFormPostPlanning.fetchScript(request)

        assertTrue(script.contains("\"X-Requested-With\":\"XMLHttpRequest\""))
        assertTrue(script.contains("\"X-CSRF-Token\":\"test-token\""))
        assertFalse(script.contains("\"Origin\":\"https://invalid.example\""))
        assertTrue(script.contains("action=wi_getreleases_pagination&pagenum=2"))
        assertTrue(script.contains("credentials:'include'"))
        assertTrue(script.contains("window.__wnaFormPostSeq=(window.__wnaFormPostSeq||0)+1"))
        assertTrue(script.contains("{seq:seq,status:"))
    }

    @Test
    fun decodesBrowserFetchResultWithoutHtmlWrapping() {
        val browserResult =
            JSONObject()
                .put("seq", 1)
                .put("status", 200)
                .put("url", "https://www.scribblehub.com/wp-admin/admin-ajax.php")
                .put("mitigated", false)
                .put("body", "<li>chapter</li>0")
                .toString()
        val callbackValue = JSONObject.quote(browserResult)

        val result = CloudflareFormPostPlanning.decodeResult(callbackValue, 1)

        assertEquals(200, result?.status)
        assertEquals("<li>chapter</li>0", result?.body)
        assertFalse(result?.mitigated ?: true)
    }

    @Test
    fun resultFromAPreviousRequestSeqIsIgnored() {
        val browserResult =
            JSONObject()
                .put("seq", 1)
                .put("status", 200)
                .put("url", "https://www.scribblehub.com/wp-admin/admin-ajax.php")
                .put("mitigated", false)
                .put("body", "<li>stale</li>")
                .toString()
        val callbackValue = JSONObject.quote(browserResult)

        assertNull(CloudflareFormPostPlanning.decodeResult(callbackValue, 2))
    }

    @Test
    fun bootstrapAndFetchMustRemainOnTheSameOrigin() {
        val request = "https://www.scribblehub.com/wp-admin/admin-ajax.php"
        assertEquals("https://www.scribblehub.com/", CloudflareFormPostPlanning.bootstrapUrl(request))
        assertTrue(CloudflareFormPostPlanning.sameOrigin(request, "https://www.scribblehub.com/series/123"))
        assertFalse(CloudflareFormPostPlanning.sameOrigin(request, "https://scribblehub.com/"))
    }

    @Test
    fun reusesOnlySettledSameOriginNonChallengePage() {
        val request = "https://www.scribblehub.com/wp-admin/admin-ajax.php"
        val page = CloudflarePageState("https://www.scribblehub.com/series/123", "complete", "<html><body>Story</body></html>")
        assertTrue(CloudflareFormPostPlanning.canReusePage(request, page))
        assertFalse(CloudflareFormPostPlanning.canReusePage(request, page.copy(stale = true)))
        assertFalse(CloudflareFormPostPlanning.canReusePage(request, page.copy(readyState = "loading")))
        assertFalse(
            CloudflareFormPostPlanning.canReusePage(
                request,
                page.copy(html = "<html><title>Just a moment...</title></html>"),
            ),
        )
    }
}
