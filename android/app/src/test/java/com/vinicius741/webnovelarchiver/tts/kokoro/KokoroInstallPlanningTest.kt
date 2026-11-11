package com.vinicius741.webnovelarchiver.tts.kokoro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class KokoroInstallPlanningTest {
    private val target = File("/data/models/staging")

    @Test
    fun `entries inside the model folder land under the target`() {
        assertEquals(File(target, "model.onnx"), KokoroInstallPlanning.entryTarget(target, "kokoro-en-v0_19/model.onnx"))
        assertEquals(
            File(target, "espeak-ng-data/lang/gmw/en-US"),
            KokoroInstallPlanning.entryTarget(target, "kokoro-en-v0_19/espeak-ng-data/lang/gmw/en-US"),
        )
        assertEquals(target, KokoroInstallPlanning.entryTarget(target, "kokoro-en-v0_19/"))
        assertEquals(File(target, "tokens.txt"), KokoroInstallPlanning.entryTarget(target, "./kokoro-en-v0_19/tokens.txt"))
    }

    @Test
    fun `entries that could escape the model folder are refused`() {
        assertNull(KokoroInstallPlanning.entryTarget(target, "kokoro-en-v0_19/../../evil"))
        assertNull(KokoroInstallPlanning.entryTarget(target, "/etc/passwd"))
        assertNull(KokoroInstallPlanning.entryTarget(target, "other-model/model.onnx"))
        assertNull(KokoroInstallPlanning.entryTarget(target, "model.onnx"))
        assertNull(KokoroInstallPlanning.entryTarget(target, "kokoro-en-v0_19\\..\\evil"))
    }

    @Test
    fun `a partial download resumes only while it is short of the whole archive`() {
        assertEquals(0L, KokoroInstallPlanning.resumeFrom(0L))
        assertEquals(1_000L, KokoroInstallPlanning.resumeFrom(1_000L))
        assertEquals(0L, KokoroInstallPlanning.resumeFrom(KokoroModel.ARCHIVE_BYTES))
        assertEquals(0L, KokoroInstallPlanning.resumeFrom(KokoroModel.ARCHIVE_BYTES + 1))
    }

    @Test
    fun `space needed shrinks as the download progresses but always covers the unpacked model`() {
        val fresh = KokoroInstallPlanning.bytesNeeded(0L)
        val done = KokoroInstallPlanning.bytesNeeded(KokoroModel.ARCHIVE_BYTES)
        assertEquals(KokoroModel.ARCHIVE_BYTES, fresh - done)
        assertTrue(done > KokoroModel.UNPACKED_BYTES)
    }

    @Test
    fun `progress reads as percent and megabytes`() {
        assertEquals(
            "Downloading 50% (159 MB of 319 MB)",
            KokoroInstallPlanning.describe(KokoroModelState.Downloading(KokoroModel.ARCHIVE_BYTES / 2, KokoroModel.ARCHIVE_BYTES)),
        )
        assertEquals("Unpacking 100%", KokoroInstallPlanning.describe(KokoroModelState.Unpacking(5L, 5L)))
        assertEquals("Installed. Uses 369 MB on this phone.", KokoroInstallPlanning.describe(KokoroModelState.Installed(369_315_617L)))
    }
}
