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
    fun fileProviderCoversPrivateFilesAndCacheExports() {
        val paths = xml("src/main/res/xml/file_paths.xml")
        val entries = listOf("files-path", "cache-path").mapNotNull { tag ->
            paths.getElementsByTagName(tag).item(0) as? Element
        }.map { element -> element.tagName to element.getAttribute("path") }.toSet()

        assertTrue(entries.contains("files-path" to "."))
        assertTrue(entries.contains("cache-path" to "."))
    }

    private fun xml(path: String) = DocumentBuilderFactory
        .newInstance()
        .newDocumentBuilder()
        .parse(File(path))
        .also { it.documentElement.normalize() }
}
