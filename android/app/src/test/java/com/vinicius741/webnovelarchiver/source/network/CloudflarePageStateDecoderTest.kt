package com.vinicius741.webnovelarchiver.source.network

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test

class CloudflarePageStateDecoderTest {
    @Test
    fun decodesBridgeEncodedPageState() {
        // What evaluateJavascript actually delivers for a script returning a string: the inner
        // JSON text, itself JSON string-encoded (quoted, with inner quotes escaped).
        val stateJson =
            """{"documentUrl":"https://www.scribblehub.com/read/1-the-story/chapter/2/",""" +
                """"readyState":"complete","stale":true,""" +
                """"html":"<html><body class=\"chapter\">Chapter 1 \"quotes\" & <b>markup</b></body></html>"}"""
        val decoded = CloudflarePageStateDecoder.decode(bridgePayload(stateJson))
        assertEquals("https://www.scribblehub.com/read/1-the-story/chapter/2/", decoded.documentUrl)
        assertEquals("complete", decoded.readyState)
        assertEquals(true, decoded.stale)
        assertEquals("<html><body class=\"chapter\">Chapter 1 \"quotes\" & <b>markup</b></body></html>", decoded.html)
    }

    @Test
    fun decodesUnencodedObjectDefensively() {
        val stateJson = """{"documentUrl":"about:blank","readyState":"loading","html":""}"""
        val decoded = CloudflarePageStateDecoder.decode(stateJson)
        assertEquals("about:blank", decoded.documentUrl)
        assertEquals("loading", decoded.readyState)
        assertEquals("", decoded.html)
        assertEquals(false, decoded.stale)
    }

    @Test
    fun decodesScriptCatchFallbackToBlankState() {
        val fallback = """{"documentUrl":"","readyState":"","html":""}"""
        val decoded = CloudflarePageStateDecoder.decode(bridgePayload(fallback))
        assertEquals(CloudflarePageState("", "", ""), decoded)
    }

    @Test
    fun returnsBlankStateForUnusablePayloads() {
        assertEquals(CloudflarePageState("", "", ""), CloudflarePageStateDecoder.decode(null))
        assertEquals(CloudflarePageState("", "", ""), CloudflarePageStateDecoder.decode("null"))
        assertEquals(CloudflarePageState("", "", ""), CloudflarePageStateDecoder.decode("Chapter text, not JSON"))
    }

    /** Reproduces the evaluateJavascript bridge encoding: the script result as a JSON string. */
    private fun bridgePayload(scriptResult: String): String =
        JSONArray()
            .put(scriptResult)
            .toString()
            .removePrefix("[")
            .removeSuffix("]")
}
