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

        assertEquals(listOf("one"), (present as DurableReadResult.Present).value)
        assertTrue(corrupt is DurableReadResult.Corrupt)
        assertEquals(99, (unsupported as DurableReadResult.UnsupportedSchema).foundVersion)
    }

    @Test
    fun decoderReadsCurrentEnvelopeAndLegacyBarePayload() {
        val envelope = gson.toJson(DurableJson.envelope(listOf("one", "two"), "test"))

        val enveloped = DurableJson.decodeText<List<String>>(envelope, gson)
        val legacy = DurableJson.decodeText<List<String>>("[\"legacy\"]", gson)

        assertEquals(listOf("one", "two"), (enveloped as DurableReadResult.Present).value)
        assertEquals(listOf("legacy"), (legacy as DurableReadResult.Present).value)
    }
}
