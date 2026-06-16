package com.vinicius741.webnovelarchiver.core

import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class AndroidManifestIntegrationTest {
    @Test
    fun manifestDeclaresPackageVisibilityForBrowserEpubAndShareTargets() {
        val manifest = xml("src/main/AndroidManifest.xml")
        val queries = manifest.getElementsByTagName("queries").item(0) as Element
        val intents = queries.getElementsByTagName("intent")
        val signatures = (0 until intents.length).map { index ->
            val intent = intents.item(index) as Element
            val action = (intent.getElementsByTagName("action").item(0) as? Element)
                ?.getAttribute("android:name")
                .orEmpty()
            val data = intent.getElementsByTagName("data").item(0) as? Element
            val scheme = data?.getAttribute("android:scheme").orEmpty()
            val mime = data?.getAttribute("android:mimeType").orEmpty()
            "$action|$scheme|$mime"
        }.toSet()

        assertTrue("https browser VIEW query missing", signatures.contains("android.intent.action.VIEW|https|"))
        assertTrue("EPUB VIEW query missing", signatures.contains("android.intent.action.VIEW||application/epub+zip"))
        assertTrue("EPUB SEND query missing", signatures.contains("android.intent.action.SEND||application/epub+zip"))
        assertTrue("JSON SEND query missing", signatures.contains("android.intent.action.SEND||application/json"))
        assertTrue("ZIP SEND query missing", signatures.contains("android.intent.action.SEND||application/zip"))
    }

    @Test
    fun fileProviderScopeNarrowedToExportDirectoriesOnly() {
        // R10: FileProvider must only expose the dedicated export/share directories (EPUBs, backups,
        // restore-exports), never the whole files/ or cache/ root which would expose private story
        // JSON and chapter HTML.
        val paths = xml("src/main/res/xml/file_paths.xml")
        val entries = (0 until paths.documentElement.childNodes.length)
            .mapNotNull { paths.documentElement.childNodes.item(it) as? Element }
            .map { element -> element.tagName to element.getAttribute("path") }

        val scopedPaths = setOf(
            "files-path" to "webnovel_archiver/epubs/",
            "files-path" to "webnovel_archiver/backups/",
            "cache-path" to "webnovel_restore/",
        )
        entries.forEach { (_, path) ->
            assertTrue("FileProvider exposes an overly-broad root path: $path", path != ".")
        }
        assertTrue("Expected scoped EPUB/backups/restore-exports entries", entries.toSet() == scopedPaths)
    }

    private fun xml(path: String) = DocumentBuilderFactory
        .newInstance()
        .newDocumentBuilder()
        .parse(File(path))
        .also { it.documentElement.normalize() }
}
