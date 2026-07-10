package com.vinicius741.webnovelarchiver.data.storage

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.vinicius741.webnovelarchiver.domain.model.AppSettings
import com.vinicius741.webnovelarchiver.domain.model.DownloadJobStatus
import com.vinicius741.webnovelarchiver.domain.model.PublicationStatus
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.ui.size
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * JVM tests for [AtomicFileWrites] and [DownloadJobStatus] (Reliability R1 + R4). These cover the
 * pure-Java durability helpers; the Android [android.util.AtomicFile]-based [DurableJson] path is
 * verified end-to-end via instrumented/Robolectric storage tests (see [StorageAtomicWriteTest]).
 */
class DurableJsonTest {
    private lateinit var dir: File
    private val gson: Gson = GsonBuilder().create()

    @Before fun setUp() {
        dir = File(System.getProperty("java.io.tmpdir"), "durable_json_test_${System.nanoTime()}")
        dir.mkdirs()
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun atomicFileWritesReplacesFileContent() {
        val target = File(dir, "chapter.html")
        AtomicFileWrites.writeText(target, "<p>first</p>")
        AtomicFileWrites.writeText(target, "<p>second</p>")
        assertEquals("<p>second</p>", target.readText())
    }

    @Test
    fun atomicFileWritesStreamProducesSameBytes() {
        val target = File(dir, "book.epub")
        val payload = ByteArray(2048) { (it % 251).toByte() }
        AtomicFileWrites.stream(target) { out -> out.write(payload) }
        assertTrue(payload.contentEquals(target.readBytes()))
    }

    @Test
    fun atomicFileWritesStreamReplacesPriorContent() {
        val target = File(dir, "book2.epub")
        AtomicFileWrites.writeBytes(target, ByteArray(512))
        AtomicFileWrites.stream(target) { out -> out.write(byteArrayOf(1, 2, 3)) }
        assertEquals(3, target.readBytes().size)
    }

    @Test
    fun envelopeSerializesAndDeserializesViaGson() {
        val jobs =
            listOf(
                mapOf("id" to "a", "status" to DownloadJobStatus.Pending.wire),
                mapOf("id" to "b", "status" to DownloadJobStatus.Completed.wire),
            )
        val envelope = DurableJson.envelope(jobs, "1.0.1-native")
        val json = gson.toJson(envelope)
        assertTrue(json.contains("\"payload\""))
        assertTrue(json.contains("\"schemaVersion\""))
        val parsed = gson.fromJson(json, DurableJson.Envelope::class.java)
        val payloadJson = gson.toJson(parsed.payload)
        val readBack = gson.fromJson(payloadJson, List::class.java)
        assertEquals(2, readBack.size)
    }

    @Test
    fun envelopeDetectsLegacyBarePayloadByPayloadKey() {
        // A legacy bare AppSettings file has no "payload" key and must fall back to a direct parse.
        val legacy = gson.toJson(AppSettings(downloadConcurrency = 4, downloadDelay = 1200, maxChaptersPerEpub = 80))
        assertTrue(!legacy.contains("\"payload\""))
    }

    @Test
    fun downloadJobStatusParsesKnownWiresAndDefaultsUnknownToFailed() {
        assertEquals(DownloadJobStatus.Pending, DownloadJobStatus.parse("pending"))
        assertEquals(DownloadJobStatus.Downloading, DownloadJobStatus.parse("downloading"))
        assertEquals(DownloadJobStatus.Cancelled, DownloadJobStatus.parse("cancelled"))
        assertEquals(DownloadJobStatus.Failed, DownloadJobStatus.parse("garbage"))
        assertEquals(DownloadJobStatus.Failed, DownloadJobStatus.parse(null))
    }

    @Test
    fun downloadJobStatusWiresCoverLegacyStringValues() {
        val wires = DownloadJobStatus.wires
        listOf("pending", "downloading", "paused", "completed", "failed", "cancelled").forEach {
            assertTrue("Missing legacy wire value: $it", wires.contains(it))
        }
    }

    @Test
    fun atomicFileWritesMissingParentIsCreated() {
        val target = File(File(dir, "nested/deep"), "out.dat")
        AtomicFileWrites.writeText(target, "ok")
        assertEquals("ok", target.readText())
    }

    /**
     * When the no-arg constructor is present (debug/unit tests), Gson applies Kotlin defaults for
     * fields missing from legacy JSON. Release builds also keep those constructors via ProGuard
     * (see proguard-rules.pro); [com.vinicius741.webnovelarchiver.domain.story.StoryNormalization]
     * additionally coerces nulls if Unsafe allocation ever leaves them unset.
     */
    @Test
    fun storyPreservesDefaultPublicationStatusWhenJsonPredatesIt() {
        val legacyJson = """{"id":"abc","title":"Legacy","chapters":[]}"""
        val story = gson.fromJson(legacyJson, Story::class.java)
        assertEquals("abc", story.id)
        assertEquals(PublicationStatus.unknown, story.publicationStatus)
    }
}
