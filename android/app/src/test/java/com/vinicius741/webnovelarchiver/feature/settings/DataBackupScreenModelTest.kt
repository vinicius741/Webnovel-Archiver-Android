package com.vinicius741.webnovelarchiver.feature.settings

import com.vinicius741.webnovelarchiver.data.repository.BackupFileEntry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class DataBackupScreenModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val host = FakeExportHost()
    private val exports = BackupExports(host, scope)
    private val data = FakeDataBackupData()

    private fun model() = DataBackupScreenModel(data, exports, CoroutineScope(SupervisorJob() + dispatcher))

    @Test
    fun anExportReportsProgressThenOffersTheFileAndRunsOneAtATime() =
        runTest(dispatcher) {
            assertTrue(exports.start())
            assertFalse("a second tap is ignored", exports.start())
            runCurrent()
            assertEquals(BackupExportProgress(), exports.running.value)

            host.progress("Copying chapters 3/9")
            assertEquals("Copying chapters 3/9", exports.running.value?.message)

            val file = File("backup.zip")
            host.result.complete(file)
            runCurrent()
            assertNull(exports.running.value)
            assertEquals(listOf(file), host.saved)
            assertEquals(1, exports.finished.value)
            assertTrue(exports.start())
        }

    @Test
    fun aFailedExportSaysWhyAndEnds() =
        runTest(dispatcher) {
            exports.start()
            runCurrent()
            host.result.completeExceptionally(IllegalStateException("Disk full"))
            runCurrent()

            assertEquals(listOf("Disk full"), host.failures)
            assertNull(exports.running.value)
            assertEquals(1, exports.finished.value)
        }

    @Test
    fun theScreenFollowsAnExportStartedBeforeItAndReloadsTheFilesWhenItEnds() =
        runTest(dispatcher) {
            exports.start()
            runCurrent()
            host.progress("Writing library")

            // Leaving Data & backup and coming back: a new model picks up the running export.
            val model = model()
            val render = launch { model.state.collect() }
            model.refresh()
            runCurrent()
            assertEquals(
                "Writing library",
                model.state.value.export
                    ?.message,
            )
            assertEquals(BackupFilesState.Loaded(emptyList()), model.state.value.files)

            host.progress("Copying chapters 1/2")
            runCurrent()
            assertEquals(
                "Copying chapters 1/2",
                model.state.value.export
                    ?.message,
            )
            val buildKey = model.state.value.buildKey
            host.progress("Copying chapters 2/2")
            runCurrent()
            assertEquals("a progress line only patches the card", buildKey, model.state.value.buildKey)

            data.files = listOf(entry("new.zip"))
            host.result.complete(File("new.zip"))
            runCurrent()
            assertNull(model.state.value.export)
            assertEquals(BackupFilesState.Loaded(listOf(entry("new.zip"))), model.state.value.files)
            assertEquals("export and file list changes patch in place", buildKey, model.state.value.buildKey)
            render.cancel()
        }

    @Test
    fun startingFromTheScreenShowsTheExportAtOnce() =
        runTest(dispatcher) {
            val model = model()
            model.startExport()
            assertEquals(BackupExportProgress(), model.state.value.export)
        }

    @Test
    fun filesThatCannotBeListedShowAsFailedAndADeleteReloadsThem() =
        runTest(dispatcher) {
            val model = model()
            data.failList = true
            model.refresh()
            runCurrent()
            assertEquals(BackupFilesState.Failed, model.state.value.files)

            data.failList = false
            data.files = listOf(entry("a.zip"), entry("b.zip"))
            var failed = false
            model.deleteBackupFile(File("a.zip")) { failed = true }
            runCurrent()
            assertFalse(failed)
            assertEquals(BackupFilesState.Loaded(listOf(entry("b.zip"))), model.state.value.files)

            data.refuseDelete = true
            model.deleteBackupFile(File("b.zip")) { failed = true }
            runCurrent()
            assertTrue(failed)
        }

    @Test
    fun storageIssuesAndClearingLocalStorage() =
        runTest(dispatcher) {
            data.issues = listOf("library.json: unreadable")
            val model = model()
            assertEquals(listOf("library.json: unreadable"), model.state.value.storageIssues)

            var cleared = false
            model.clearLocalStorage { cleared = true }
            runCurrent()
            assertTrue(data.clearedAll)
            assertTrue(cleared)
        }

    private fun entry(name: String) = BackupFileEntry(File(name), sizeBytes = 10L, lastModifiedMillis = 1L)
}

private class FakeExportHost : BackupExportHost {
    val result = CompletableDeferred<File>()
    private var onProgress: ((String) -> Unit)? = null
    val saved = mutableListOf<File>()
    val failures = mutableListOf<String>()

    fun progress(message: String) = onProgress?.invoke(message)

    override suspend fun export(onProgress: (String) -> Unit): File {
        this.onProgress = onProgress
        return result.await()
    }

    override fun showSaved(file: File) {
        saved += file
    }

    override fun showFailed(message: String) {
        failures += message
    }
}

private class FakeDataBackupData : DataBackupData {
    var files: List<BackupFileEntry> = emptyList()
    var failList = false
    var refuseDelete = false
    var issues: List<String> = emptyList()
    var clearedAll = false

    override suspend fun backupFiles(): List<BackupFileEntry> = if (failList) error("unreadable") else files

    override suspend fun deleteBackupFile(file: File): Boolean {
        if (refuseDelete) return false
        files = files.filterNot { it.file == file }
        return true
    }

    override fun storageIssues(): List<String> = issues

    override suspend fun clearAll() {
        clearedAll = true
    }
}
