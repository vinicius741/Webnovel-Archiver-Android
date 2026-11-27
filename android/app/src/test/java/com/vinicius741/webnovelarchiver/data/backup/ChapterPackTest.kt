package com.vinicius741.webnovelarchiver.data.backup

import com.github.luben.zstd.ZstdOutputStreamNoFinalizer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.ZipException

class ChapterPackTest {
    @Test
    fun roundTripsChapterFilesInOrderAndLeavesTheOuterStreamOpen() {
        val outer = TrackingOutput()
        val files = listOf("0000_a.html" to "<p>One</p>".repeat(200), "0001_b%C3%A9.html" to "", "0002_c.html" to "<p>Two ✓</p>")
        ChapterPack.Writer(outer).use { writer -> files.forEach { (name, text) -> writer.add(name, text.toByteArray()) } }
        assertTrue(!outer.closed)

        val read = mutableListOf<Pair<String, String>>()
        ChapterPack.read(ByteArrayInputStream(outer.toByteArray()), Long.MAX_VALUE) { name, size, content ->
            val bytes = content.readBytes()
            assertEquals(size, bytes.size.toLong())
            read += name to bytes.decodeToString()
        }
        assertEquals(files, read)
    }

    @Test
    fun aCallbackThatSkipsContentStillAdvancesToTheNextFile() {
        val pack = pack("0000_a.html" to "first", "0001_b.html" to "second")
        val names = mutableListOf<String>()
        ChapterPack.read(ByteArrayInputStream(pack), Long.MAX_VALUE) { name, _, _ -> names += name }
        assertEquals(listOf("0000_a.html", "0001_b.html"), names)
    }

    @Test
    fun rejectsAFileLargerThanTheLimitBeforeReadingIt() {
        val pack = pack("0000_a.html" to "x".repeat(1_000))
        var called = false
        val error =
            assertThrows(IllegalStateException::class.java) {
                ChapterPack.read(ByteArrayInputStream(pack), 999) { _, _, _ -> called = true }
            }
        assertEquals("Backup entry too large: 0000_a.html", error.message)
        assertTrue(!called)
    }

    @Test
    fun writerRejectsNamesThatAreNotBareFileNames() {
        listOf("", ".", "..", "a/b.html", "a\\b.html", "a\u0000.html").forEach { name ->
            assertThrows(IllegalArgumentException::class.java) {
                ChapterPack.Writer(ByteArrayOutputStream()).add(name, ByteArray(0))
            }
        }
    }

    @Test
    fun readerRejectsHostileRecords() {
        fun rejects(body: DataOutputStream.() -> Unit) {
            assertThrows(ZipException::class.java) {
                ChapterPack.read(ByteArrayInputStream(rawPack(body)), Long.MAX_VALUE) { _, _, content -> content.readBytes() }
            }
        }
        // Directory traversal and separators in a file name.
        rejects { record("../x.html", "x") }
        rejects { record("a/b.html", "x") }
        // Invalid UTF-8 name.
        rejects {
            writeShort(2)
            write(byteArrayOf(0xC3.toByte(), 0x28))
            writeInt(0)
            writeShort(0)
        }
        // Declared size longer than the stream.
        rejects {
            writeShort(5)
            write("a.htm".toByteArray())
            writeInt(100)
            write("short".toByteArray())
        }
        // Missing terminator, and data after it.
        rejects { record("a.html", "x") }
        rejects {
            record("a.html", "x")
            writeShort(0)
            write(1)
        }
    }

    @Test
    fun rejectsBadHeaderCorruptFramesAndOversizedWindows() {
        val read = { bytes: ByteArray -> ChapterPack.read(ByteArrayInputStream(bytes), Long.MAX_VALUE) { _, _, c -> c.readBytes() } }
        assertThrows(ZipException::class.java) { read(zstd(windowLog = null) { write("NOTAPACK".toByteArray()) }) }
        assertThrows(ZipException::class.java) { read("not zstd at all".toByteArray()) }
        val good = pack("0000_a.html" to "x".repeat(10_000))
        assertThrows(ZipException::class.java) { read(good.copyOf(good.size / 2)) }
        // A frame that asks for a 32 MB window is refused before the decoder allocates it.
        val wide =
            zstd(windowLog = 25) {
                writeMagic()
                record("a.html", "x")
                writeShort(0)
            }
        assertThrows(ZipException::class.java) { read(wide) }
    }

    @Test
    fun packingSharesRedundancyAcrossChapters() {
        val chapter = (1..200).joinToString("") { "<p class=\"cnABCDEFGH\">Line $it of a chapter.</p>" }
        val files = (0 until 50).map { "%04d_c$it.html".format(it) to chapter.replace("chapter", "chapter $it") }
        val packed = pack(*files.toTypedArray())
        val raw = files.sumOf { it.second.length }
        assertTrue("pack ${packed.size} bytes for $raw raw", packed.size * 20 < raw)
        val roundTrip = mutableListOf<ByteArray>()
        ChapterPack.read(ByteArrayInputStream(packed), Long.MAX_VALUE) { _, _, c -> roundTrip += c.readBytes() }
        assertArrayEquals(files.last().second.toByteArray(), roundTrip.last())
    }

    private fun pack(vararg files: Pair<String, String>): ByteArray =
        ByteArrayOutputStream()
            .also { out -> ChapterPack.Writer(out).use { w -> files.forEach { (n, t) -> w.add(n, t.toByteArray()) } } }
            .toByteArray()

    private fun rawPack(body: DataOutputStream.() -> Unit): ByteArray =
        zstd(windowLog = null) {
            writeMagic()
            body()
        }

    private fun zstd(
        windowLog: Int?,
        body: DataOutputStream.() -> Unit,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val zstd = ZstdOutputStreamNoFinalizer(out, 3)
        windowLog?.let(zstd::setWindowLog)
        DataOutputStream(zstd).use { it.body() }
        return out.toByteArray()
    }

    private fun DataOutputStream.writeMagic() =
        write(
            byteArrayOf(
                'W'.code.toByte(),
                'N'.code.toByte(),
                'A'.code.toByte(),
                'P'.code.toByte(),
                'A'.code.toByte(),
                'C'.code.toByte(),
                'K'.code.toByte(),
                1,
            ),
        )

    private fun DataOutputStream.record(
        name: String,
        text: String,
    ) {
        val bytes = name.toByteArray()
        writeShort(bytes.size)
        write(bytes)
        writeInt(text.length)
        write(text.toByteArray())
    }

    private class TrackingOutput : ByteArrayOutputStream() {
        var closed = false

        override fun close() {
            closed = true
        }
    }
}
