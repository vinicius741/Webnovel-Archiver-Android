package com.vinicius741.webnovelarchiver.source

import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkRequestsTest {
    @Test
    fun pageRequestMatchesReactNativeFetcherHeaders() {
        val request = NetworkRequests.pageRequest("https://example.com/story")

        assertEquals(NetworkRequests.USER_AGENT, request.header("User-Agent"))
        assertEquals(NetworkRequests.DEFAULT_ACCEPT, request.header("Accept"))
        assertEquals("en-US,en;q=0.9", request.header("Accept-Language"))
    }

    @Test
    fun formRequestMatchesReactNativeFetcherHeadersAndBodyShape() {
        val request =
            NetworkRequests.formRequest(
                "https://www.scribblehub.com/wp-admin/admin-ajax.php",
                mapOf("action" to "wi_getreleases_pagination", "pagenum" to 2, "mypostid" to 123),
            )
        val buffer = Buffer()
        request.body?.writeTo(buffer)

        assertEquals("POST", request.method)
        assertEquals(NetworkRequests.USER_AGENT, request.header("User-Agent"))
        assertEquals(NetworkRequests.FORM_ACCEPT, request.header("Accept"))
        assertEquals("en-US,en;q=0.9", request.header("Accept-Language"))
        assertEquals(NetworkRequests.FORM_CONTENT_TYPE, request.header("Content-Type"))
        assertEquals("XMLHttpRequest", request.header("X-Requested-With"))
        assertEquals("action=wi_getreleases_pagination&pagenum=2&mypostid=123", buffer.readUtf8())
    }
}
