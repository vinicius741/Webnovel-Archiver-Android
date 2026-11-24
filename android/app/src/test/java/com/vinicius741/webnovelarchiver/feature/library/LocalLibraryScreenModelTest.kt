package com.vinicius741.webnovelarchiver.feature.library

import com.vinicius741.webnovelarchiver.source.local.LocalBookFormat
import com.vinicius741.webnovelarchiver.source.local.LocalEntry
import com.vinicius741.webnovelarchiver.source.local.LocalSourceUrls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalLibraryScreenModelTest {
    private val names = listOf("A", "B", "C")
    private val library = mutableSetOf<String>()

    private fun entry(name: String) =
        LocalEntry(name, true, LocalSourceUrls.story(name), LocalSourceUrls.storyId(name), 1, listOf(LocalBookFormat.TEXT))

    private fun scannedModel(): LocalLibraryScreenModel =
        LocalLibraryScreenModel(
            scan = { LocalScan("Books", names.map(::entry)) },
            libraryIds = { library.toSet() },
            chooseFolder = {},
            scope = CoroutineScope(Job() + Dispatchers.Unconfined),
            io = Dispatchers.Unconfined,
        ).apply { rescan() }

    @Test
    fun addAllGoesPastAFailedNovelAndListsWhy() {
        val model = scannedModel()
        assertEquals(3, model.state.value.notInLibrary.size)

        assertEquals(LocalSourceUrls.story("A"), model.startImportAll())
        library += LocalSourceUrls.storyId("A")
        assertEquals(LocalSourceUrls.story("B"), model.importFinished())
        assertEquals(LocalSourceUrls.story("C"), model.importFailed("No text in \"B\"."))
        assertNull(model.importFinished())

        val state = model.state.value
        assertEquals(listOf("B: No text in \"B\"."), state.failures)
        assertEquals(listOf("B", "C"), state.notInLibrary.map { it.entry.name })
        assertTrue(!state.importing)
    }

    @Test
    fun anAddAllRunStopsWhenTheScreenIsGone() {
        val model = scannedModel()
        model.startImportAll()

        model.clear()

        assertNull(model.importFinished())
    }

    @Test
    fun aFailedScanOffersTheFolderPickerInsteadOfScanningForever() {
        var scans = 0
        val model =
            LocalLibraryScreenModel(
                scan = {
                    scans++
                    error("Choose your local folder again.")
                },
                libraryIds = { emptySet() },
                chooseFolder = {},
                scope = CoroutineScope(Job() + Dispatchers.Unconfined),
                io = Dispatchers.Unconfined,
            )

        model.rescan()

        val state = model.state.value
        assertTrue(state.scanned && !state.scanning && !state.folderChosen)
        assertEquals("Choose your local folder again.", state.error)
        assertEquals(1, scans)
    }
}
