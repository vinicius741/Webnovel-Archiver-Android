package com.vinicius741.webnovelarchiver.data.storage

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableReadResultTest {
    private val gson = Gson()

    @Test
    fun decoderDistinguishesPresentCorruptAndUnsupportedSchema() {
        val present = DurableJson.decodeText<List<String>>("[\"one\"]", gson)
        val corrupt = DurableJson.decodeText<List<String>>("{not-json", gson)
        val unsupported =
            DurableJson.decodeText<List<String>>(
                """{"schemaVersion":99,"payload":["one"]}""",
                gson,
            )
        val incompatibleFuturePayload =
            DurableJson.decodeText<List<String>>(
                """{"schemaVersion":99,"payload":{"futureShape":true}}""",
                gson,
            )
        val reorderedFuturePayload =
            DurableJson.decodeText<List<String>>(
                """{"payload":{"futureShape":true},"schemaVersion":99}""",
                gson,
            )

        assertEquals(listOf("one"), (present as DurableReadResult.Present).value)
        assertTrue(corrupt is DurableReadResult.Corrupt)
        assertEquals(99, (unsupported as DurableReadResult.UnsupportedSchema).foundVersion)
        assertEquals(99, (incompatibleFuturePayload as DurableReadResult.UnsupportedSchema).foundVersion)
        assertEquals(99, (reorderedFuturePayload as DurableReadResult.UnsupportedSchema).foundVersion)
    }

    @Test
    fun decoderReadsCurrentEnvelopeAndLegacyBarePayload() {
        val envelope = gson.toJson(DurableJson.envelope(listOf("one", "two"), "test"))

        val enveloped = DurableJson.decodeText<List<String>>(envelope, gson)
        val legacy = DurableJson.decodeText<List<String>>("[\"legacy\"]", gson)

        assertEquals(listOf("one", "two"), (enveloped as DurableReadResult.Present).value)
        assertEquals(listOf("legacy"), (legacy as DurableReadResult.Present).value)
    }

    @Test
    fun decoderStreamsPayloadEvenWhenEnvelopeFieldsAreReordered() {
        val result =
            DurableJson.decodeText<List<String>>(
                """{"payload":["one","two"],"appVersion":"test","schemaVersion":1}""",
                gson,
            )

        assertEquals(listOf("one", "two"), (result as DurableReadResult.Present).value)
    }

    @Test
    fun decoderPreservesLegacyObjectAndRejectsMalformedEnvelope() {
        val legacy = DurableJson.decodeText<Map<String, Int>>("""{"count":2}""", gson)
        val missingSchema = DurableJson.decodeText<List<String>>("""{"payload":["one"]}""", gson)
        val trailing = DurableJson.decodeText<List<String>>("""{"schemaVersion":1,"payload":[]} true""", gson)

        assertEquals(mapOf("count" to 2), (legacy as DurableReadResult.Present).value)
        assertTrue(missingSchema is DurableReadResult.Corrupt)
        assertTrue(trailing is DurableReadResult.Corrupt)
    }
}
