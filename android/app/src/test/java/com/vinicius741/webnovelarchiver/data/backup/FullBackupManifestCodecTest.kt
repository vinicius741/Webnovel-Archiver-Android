package com.vinicius741.webnovelarchiver.data.backup

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.model.Tab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/** Version-2 manifest writer and streaming reader, plus restore of version-1 manifests. */
class FullBackupManifestCodecTest {
    private val gson = Gson()

    @Test
    fun derivedChapterIndexMatchesTheChapterEntriesTheExporterZips() {
        val library = sampleLibrary()
        val plan = planOf(library)
        // The exporter zips exactly the planned FromFile and Inline chapters.
        val zipped = plan.filterNot { it is FullBackupContentPlanning.ChapterContent.Missing }.map { it.path() }

        val parsed = FullBackupManifestValidation.parseValidated(read(writeV2(library, plan)))

        assertEquals(FullBackupManifestValidation.CURRENT_VERSION, parsed.version)
        assertEquals(zipped, parsed.chapterFiles.map { it.path })
        assertEquals(
            plan.filterNot { it is FullBackupContentPlanning.ChapterContent.Missing }.map { it.storyId to it.chapterId },
            parsed.chapterFiles.map { it.storyId to it.chapterId },
        )
        // The missing chapter is reported and left out, and the not-downloaded one never appears.
        assertFalse(parsed.chapterFiles.any { it.chapterId == "a-3" || it.chapterId == "a-4" })
    }

    @Test
    fun versionTwoRoundTripsStoriesConfigAndIndexes() {
        val library = sampleLibrary()
        val parsed = FullBackupManifestValidation.parseValidated(read(writeV2(library, planOf(library))))

        assertEquals(library, parsed.library)
        assertEquals(listOf(tab), gson.fromJson(parsed.config.get("tabs"), Array<Tab>::class.java).toList())
        assertEquals(listOf(RestoredMetricFileIndex("story-a", "metrics/story-a.json")), parsed.metricFiles)
        assertEquals(listOf(RestoredCoverFileIndex("story-b", "covers/story-b.png")), parsed.coverFiles)
        assertTrue(parsed.rewriteFiles.isEmpty())
    }

    @Test
    fun versionTwoIsCompactAndListsNoChapterFiles() {
        val library = sampleLibrary()
        val json = writeV2(library, planOf(library)).toString(Charsets.UTF_8)

        assertFalse(json.contains('\n'))
        assertFalse(json.contains("chapterFiles"))
        // HTML escaping is off: titles keep their characters instead of six-byte escapes.
        assertTrue(json.contains("Reading <now> & later"))
    }

    @Test
    fun versionOneManifestsStillRestoreWithTheirListedIndex() {
        val library = sampleLibrary()
        val plan = planOf(library)
        val v1 = FullBackupManifestValidation.parseValidated(read(writeV1(library, plan)))
        val v2 = FullBackupManifestValidation.parseValidated(read(writeV2(library, plan)))

        assertEquals(1, v1.version)
        assertEquals(v2.library, v1.library)
        assertEquals(v2.chapterFiles, v1.chapterFiles)
        assertEquals(v2.config, v1.config)
    }

    @Test
    fun versionTwoRejectsAListedChapterIndex() {
        val json = """{"format":"webnovel-archiver-full-backup","version":2,"library":[],"config":{"tabs":[]},"chapterFiles":[]}"""
        assertEquals("Invalid full backup: unexpected chapter file index", FullBackupManifestValidation.validate(readJson(json)))
    }

    @Test
    fun versionTwoValidatesTheDerivedIndexAndMissingContent() {
        fun v2(
            chapters: String,
            missing: String = "[]",
        ) = FullBackupManifestValidation.validate(
            readJson(
                """{"format":"webnovel-archiver-full-backup","version":2,"config":{"tabs":[]},""" +
                    """"library":[{"id":"s","chapters":$chapters}],"missingContent":$missing}""",
            ),
        )

        assertNull(v2("""[{"id":"c1","downloaded":true},{"id":"c2"}]"""))
        assertEquals("Invalid full backup: malformed chapter file index", v2("""[{"id":"","downloaded":true}]"""))
        assertEquals(
            "Invalid full backup: duplicate chapter entries",
            v2("""[{"id":"c1","downloaded":true},{"id":"c1","downloaded":true}]"""),
        )
        assertEquals(
            "Invalid full backup: malformed missing content index",
            v2("""[]""", missing = """[{"kind":"chapter","storyId":"other","chapterId":"c1"}]"""),
        )
        assertEquals("Invalid full backup: malformed missing content index", v2("""[]""", missing = """"x""""))
        // Kinds other than chapters are reports only.
        assertNull(v2("""[]""", missing = """[{"kind":"cover","storyId":"s"}]"""))
    }

    @Test
    fun streamingReaderKeepsTheFormerParseErrors() {
        assertEquals("Invalid full backup: manifest was empty", assertThrows(IllegalStateException::class.java) { readJson("") }.message)
        assertEquals(
            "Invalid full backup: manifest was empty",
            assertThrows(IllegalStateException::class.java) { readJson("null") }.message,
        )
        assertThrows(JsonSyntaxException::class.java) { readJson("""{"format":"a","format":"b"}""") }
        assertThrows(JsonSyntaxException::class.java) { readJson("""{"format":""") }
        assertThrows(JsonSyntaxException::class.java) { readJson("""{} []""") }
        assertThrows(JsonSyntaxException::class.java) { readJson("""[]""") }
    }

    @Test
    fun streamingReaderEnforcesTheByteLimit() {
        val error =
            assertThrows(IllegalStateException::class.java) {
                FullBackupManifestReader.read(gson, ByteArray(3 * 1024 * 1024) { ' '.code.toByte() }.inputStream(), 2L * 1024 * 1024)
            }
        assertEquals("Full-backup manifest exceeds the 2 MB input limit", error.message)
    }

    @Test
    fun aStoryThatDoesNotDecodeFailsAfterValidation() {
        val json = """{"format":"webnovel-archiver-full-backup","version":2,"library":[{"id":"s","chapters":"x"}],"config":{"tabs":[]}}"""
        val raw = readJson(json)
        assertNull(FullBackupManifestValidation.validate(raw))
        assertThrows(JsonSyntaxException::class.java) { FullBackupManifestValidation.parseValidated(raw) }
        // Validation errors still come first, as with the former Map parse.
        assertEquals(
            "Invalid full backup: unsupported format",
            FullBackupManifestValidation.validate(readJson(json.replace("webnovel-archiver-full-backup", "other"))),
        )
    }

    private fun sampleLibrary(): List<Story> =
        listOf(
            Story(
                id = "story-a",
                title = "A <tale>",
                chapters =
                    mutableListOf(
                        Chapter(id = "a-1", title = "One", url = "https://example.test/a/1", downloaded = true, downloadedAt = 1L),
                        Chapter(id = "a-2", title = "Two", url = "https://example.test/a/2", downloaded = true),
                        Chapter(id = "a-3", title = "Three (missing)", url = "https://example.test/a/3", downloaded = true),
                        Chapter(id = "a-4", title = "Four", url = "https://example.test/a/4"),
                        Chapter(id = "a/5 é", title = "Five", url = "https://example.test/a/5", downloaded = true),
                    ),
            ),
            Story(
                id = "story-b",
                title = "B",
                aiCoverPath = "covers/story-b.png",
                chapters = mutableListOf(Chapter(id = "b-1", title = "Inline", downloaded = true, content = null)),
            ),
        )

    /** a-1, a-2, a/5 é: files; b-1: legacy inline content; a-3: missing. */
    private fun planOf(library: List<Story>): List<FullBackupContentPlanning.ChapterContent> =
        FullBackupContentPlanning.planChapterContent(
            library =
                library.map { story ->
                    story.copy(
                        chapters =
                            story.chapters
                                .map {
                                    it.copy(
                                        content =
                                            if (it.id ==
                                                "b-1"
                                            ) {
                                                "<p>x</p>"
                                            } else {
                                                null
                                            },
                                    )
                                }.toMutableList(),
                    )
                },
            resolveFile = { chapter -> File("/unused/${chapter.id}").takeIf { chapter.id != "a-3" && chapter.id != "b-1" } },
            chapterPath = FullBackupPaths::chapterPath,
        )

    private fun writeV2(
        library: List<Story>,
        plan: List<FullBackupContentPlanning.ChapterContent>,
    ): ByteArray =
        ByteArrayOutputStream()
            .also { out ->
                FullBackupManifestWriter.write(
                    out,
                    FullBackupManifestContent(
                        exportDate = "2026-11-27T00:00:00Z",
                        library = library,
                        config = config(),
                        metricFiles = listOf(RestoredMetricFileIndex("story-a", "metrics/story-a.json")),
                        coverFiles = listOf(RestoredCoverFileIndex("story-b", "covers/story-b.png")),
                        missingContent = FullBackupContentPlanning.missingContentReport(plan),
                    ),
                )
            }.toByteArray()

    /** The version-1 manifest exactly as the former exporter built it (pretty Gson of a Map). */
    private fun writeV1(
        library: List<Story>,
        plan: List<FullBackupContentPlanning.ChapterContent>,
    ): ByteArray =
        GsonBuilder()
            .setPrettyPrinting()
            .create()
            .toJson(
                mapOf(
                    "format" to "webnovel-archiver-full-backup",
                    "version" to 1,
                    "exportDate" to "2026-11-27T00:00:00Z",
                    "library" to library,
                    "config" to config(),
                    "chapterFiles" to
                        plan.filterNot { it is FullBackupContentPlanning.ChapterContent.Missing }.map {
                            mapOf("storyId" to it.storyId, "chapterId" to it.chapterId, "path" to it.path())
                        },
                    "metricFiles" to listOf(mapOf("storyId" to "story-a", "path" to "metrics/story-a.json")),
                    "coverFiles" to listOf(mapOf("storyId" to "story-b", "path" to "covers/story-b.png")),
                ),
            ).toByteArray()

    private val tab = Tab(id = "tab-1", name = "Reading <now> & later", order = 0, createdAt = 5L)

    private fun config(): Map<String, Any?> = mapOf("tabs" to listOf(tab))

    private fun FullBackupContentPlanning.ChapterContent.path(): String =
        when (this) {
            is FullBackupContentPlanning.ChapterContent.FromFile -> path
            is FullBackupContentPlanning.ChapterContent.Inline -> path
            is FullBackupContentPlanning.ChapterContent.Missing -> error("missing has no path")
        }

    private fun read(bytes: ByteArray): RawFullBackupManifest =
        FullBackupManifestReader.read(gson, bytes.inputStream(), BackupInputLimits.MAX_MANIFEST_BYTES)

    private fun readJson(json: String): RawFullBackupManifest = read(json.toByteArray())
}
