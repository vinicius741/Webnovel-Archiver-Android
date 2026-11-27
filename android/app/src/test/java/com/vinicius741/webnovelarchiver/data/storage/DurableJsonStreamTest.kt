package com.vinicius741.webnovelarchiver.data.storage

import com.google.gson.Gson
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.Story
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

/** Streaming envelope writer and reader behind [DurableJson.writeAtomic] / [DurableJson.readAtomicResult]. */
class DurableJsonStreamTest {
    private val gson = SharedGson.plain
    private val story =
        Story(
            id = "rr_1",
            title = "Tom & Jerry <\"quoted\"> ação",
            chapters =
                mutableListOf(
                    Chapter(id = "1", title = "Chapter 1 – One", url = "https://example.com/1?a=b&c=d"),
                    Chapter(id = "2", title = "Chapter 2", url = "https://example.com/2"),
                ),
        )

    private fun encode(
        value: Any?,
        appVersion: String? = "1.2.12-native",
        writer: Gson = gson,
    ): ByteArray =
        ByteArrayOutputStream()
            .also { DurableJson.writeEnvelope(it, writer, DurableJson.envelope(value, appVersion)) }
            .toByteArray()

    private inline fun <reified T> decode(bytes: ByteArray): Pair<DurableReadResult<T>, Int> {
        var opens = 0
        val result =
            DurableJson.decodeStreamOfType<T>(gson, T::class.java) {
                opens += 1
                ByteArrayInputStream(bytes)
            }
        return result to opens
    }

    @Test
    fun writerIsCompactPutsSchemaFirstAndMatchesGsonBytes() {
        val bytes = encode(story)
        val text = String(bytes, Charsets.UTF_8)

        assertTrue(text.startsWith("""{"schemaVersion":1,"appVersion":"1.2.12-native","payload":{"""))
        assertFalse(text.contains('\n'))
        // Same bytes Gson produces for the whole envelope (the JVM keeps declaration order).
        assertEquals(gson.toJson(DurableJson.envelope(story, "1.2.12-native")), text)
    }

    @Test
    fun writerOmitsNullAppVersionAndHonoursPrettyGson() {
        val bare = String(encode(listOf("a"), appVersion = null), Charsets.UTF_8)
        val pretty = String(encode(listOf("a"), writer = SharedGson.pretty), Charsets.UTF_8)

        assertEquals("""{"schemaVersion":1,"payload":["a"]}""", bare)
        assertEquals(SharedGson.pretty.toJson(DurableJson.envelope(listOf("a"), "1.2.12-native")), pretty)
    }

    @Test
    fun compactSchemaFirstDocumentDecodesInOnePass() {
        val (result, opens) = decode<Story>(encode(story))

        assertEquals(story, (result as DurableReadResult.Present).value)
        assertEquals(1, opens)
    }

    @Test
    fun prettyDocumentWithPayloadBeforeSchemaStillDecodes() {
        // The shape every library file has on device today: pretty, ART field order (by name).
        val payload = SharedGson.pretty.toJson(story)
        val legacyOrder = "{\n  \"appVersion\": \"1.2.3-native\",\n  \"payload\": $payload,\n  \"schemaVersion\": 1\n}"

        val (result, opens) = decode<Story>(legacyOrder.toByteArray())

        assertEquals(story, (result as DurableReadResult.Present).value)
        assertEquals(2, opens)
    }

    @Test
    fun futureSchemaIsInspectedBeforeThePayloadIsDecoded() {
        val bytes = """{"payload":{"futureShape":[1,2]},"schemaVersion":99}""".toByteArray()

        val (result, opens) = decode<Story>(bytes)

        assertEquals(99, (result as DurableReadResult.UnsupportedSchema).foundVersion)
        assertEquals(1, opens)
    }

    @Test
    fun bareLegacyDocumentDecodesAsThePayload() {
        val (result, _) = decode<Story>(gson.toJson(story).toByteArray())

        assertEquals(story, (result as DurableReadResult.Present).value)
    }

    @Test
    fun malformedOrTruncatedJsonIsCorrupt() {
        val full = encode(story)
        val truncated = full.copyOf(full.size / 2)

        assertTrue(decode<Story>(truncated).first is DurableReadResult.Corrupt)
        assertTrue(decode<Story>("{not-json".toByteArray()).first is DurableReadResult.Corrupt)
        assertTrue(decode<Story>(ByteArray(0)).first is DurableReadResult.Corrupt)
    }

    @Test
    fun sourceFailureMidReadIsIoFailureNotCorrupt() {
        val bytes = encode(story)
        val failure = IOException("EIO")
        val result =
            DurableJson.decodeStreamOfType<Story>(gson, Story::class.java) {
                FailingAfter(bytes, failAt = bytes.size / 2, failure = failure)
            }

        assertEquals(failure, (result as DurableReadResult.IoFailure).cause)
    }

    @Test
    fun sourceThatCannotOpenIsIoFailure() {
        val failure = FileNotFoundException("gone")
        val result = DurableJson.decodeStreamOfType<Story>(gson, Story::class.java) { throw failure }

        assertEquals(failure, (result as DurableReadResult.IoFailure).cause)
    }

    @Test
    fun textDecoderStillMatchesStreamDecoder() {
        val text = String(encode(story), Charsets.UTF_8)

        assertEquals(DurableJson.decodeText<Story>(text, gson), decode<Story>(text.toByteArray()).first)
    }

    private class FailingAfter(
        private val bytes: ByteArray,
        private val failAt: Int,
        private val failure: IOException,
    ) : InputStream() {
        private var position = 0

        override fun read(): Int {
            if (position >= failAt) throw failure
            return if (position < bytes.size) bytes[position++].toInt() and 0xFF else -1
        }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            if (len == 0) return 0
            if (position >= failAt) throw failure
            val count = minOf(len, failAt - position, bytes.size - position)
            if (count <= 0) return -1
            System.arraycopy(bytes, position, b, off, count)
            position += count
            return count
        }
    }
}
