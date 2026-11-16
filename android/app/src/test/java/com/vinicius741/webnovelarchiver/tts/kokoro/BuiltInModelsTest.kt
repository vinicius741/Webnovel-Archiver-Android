package com.vinicius741.webnovelarchiver.tts.kokoro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltInModelsTest {
    @Test
    fun theOriginalModelKeepsItsIdsAndCacheKeys() {
        // Packs and cached clips already on phones were made under these.
        val bella = KokoroVoices.forIdentifier("kokoro-en-v0_19:af_bella")!!
        assertEquals(BuiltInModel.KOKORO_V019, bella.model)
        assertEquals("builtin:kokoro-en-v0_19|speaker=1|text=${KokoroTextPlanning.VERSION}", KokoroVoices.voiceKey(bella))
        assertEquals(KokoroVoices.voiceKey(1), KokoroVoices.voiceKey(bella))
        assertEquals("builtin:kokoro-en-v0_19", KokoroVoices.ENGINE_ID)
    }

    @Test
    fun everyVoiceRoundTripsToItsOwnModel() {
        BuiltInModel.entries.forEach { model ->
            model.voices.forEach { voice ->
                val id = KokoroVoices.identifier(voice)
                assertTrue(id, id.startsWith(model.idPrefix))
                assertEquals(voice, KokoroVoices.forIdentifier(id))
                assertEquals(model, voice.model)
            }
            assertEquals(model, BuiltInModel.forEngine(model.engineId))
            assertTrue(KokoroVoices.isEngine(model.engineId))
            assertEquals(
                model.voices.size,
                model.voices
                    .map { it.speakerId }
                    .toSet()
                    .size,
            )
        }
        assertNull(BuiltInModel.forIdentifier("kokoro-multi-lang-v1_0:bf_emma"))
        assertFalse(KokoroVoices.isEngine("com.google.android.tts"))
    }

    @Test
    fun kokoroV1SpeakersMatchTheExportsOrder() {
        // speaker2id in kokoro-multi-lang-v1_0's model.onnx metadata.
        val ids = BuiltInModel.KOKORO_V1.voices.associate { it.code to it.speakerId }
        assertEquals(2, ids["af_bella"])
        assertEquals(3, ids["af_heart"])
        assertEquals(10, ids["af_sky"])
        assertEquals(11, ids["am_adam"])
        assertEquals(19, ids["am_santa"])
        assertEquals("af_heart", BuiltInModel.KOKORO_V1.defaultVoice.code)
    }

    @Test
    fun phononVoicesGoByNameInTheirKeys() {
        val harper = BuiltInModel.PHONON.defaultVoice
        assertEquals("Harper", harper.code)
        assertEquals("builtin:phonon-31m-en|voice=Harper|text=${PhononTextPlanning.VERSION}", KokoroVoices.voiceKey(harper))
        assertEquals(
            listOf("Harper", "Everly", "Bryony", "Hadley", "Juniper"),
            BuiltInModel.PHONON.voices.map { it.code },
        )
    }

    @Test
    fun aVoiceOfAnotherModelFallsBackToTheModelsDefault() {
        val bella = KokoroVoices.forIdentifier("kokoro-en-v0_19:af_bella")
        assertEquals(BuiltInModel.PHONON.defaultVoice, KokoroVoices.voiceFor(BuiltInModel.PHONON, "kokoro-en-v0_19:af_bella"))
        assertEquals(bella, KokoroVoices.voiceFor(BuiltInModel.KOKORO_V019, "kokoro-en-v0_19:af_bella"))
        assertEquals(BuiltInModel.KOKORO_V1.defaultVoice, KokoroVoices.voiceFor(BuiltInModel.KOKORO_V1, null))
    }

    @Test
    fun phononTextLosesOnlyAsterisks() {
        assertEquals("“Run…” he said.", PhononTextPlanning.spokenText("*“Run…”*   he said."))
        assertEquals("“Go home.”", BuiltInModel.PHONON.spokenText("“Go home.”"))
        // Kokoro keeps its own cleanup.
        assertEquals("“Go home.", BuiltInModel.KOKORO_V1.spokenText("“Go home.”"))
    }

    @Test
    fun packagesAddUp() {
        val phonon = BuiltInModel.PHONON.modelPackage as ModelPackage.Files
        assertEquals(phonon.files.sumOf { it.bytes }, phonon.downloadBytes)
        assertTrue(phonon.files.any { it.path == "voices/Harper.safetensors" })
        assertEquals(BuiltInModel.PHONON.voices.size, phonon.files.count { it.path.startsWith("voices/") })
        phonon.files.forEach { assertEquals(it.path, 64, it.sha256.length) }
        val v1 = BuiltInModel.KOKORO_V1.modelPackage as ModelPackage.Archive
        assertTrue(v1.requiredFiles.contains("lexicon-us-en.txt"))
        assertEquals(KokoroModel.ARCHIVE_BYTES, BuiltInModel.KOKORO_V019.modelPackage.downloadBytes)
    }

    @Test
    fun separateFilesNeedRoomOnceAndAnArchiveTwice() {
        val phonon = BuiltInModel.PHONON.modelPackage
        val v1 = BuiltInModel.KOKORO_V1.modelPackage
        val margin = KokoroInstallPlanning.bytesNeeded(phonon.downloadBytes, phonon)
        assertEquals(phonon.downloadBytes, KokoroInstallPlanning.bytesNeeded(0L, phonon) - margin)
        assertEquals(v1.downloadBytes + v1.unpackedBytes, KokoroInstallPlanning.bytesNeeded(0L, v1) - margin)
    }

    @Test
    fun phononNeedsDotProductAndHalfPrecisionOnEveryCore() {
        val modern =
            """
            processor : 0
            Features : fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp
            processor : 1
            Features : fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp
            """.trimIndent()
        assertTrue(PhononCpu.hasArmFeatures(modern))
        // An ARMv8.0 core (or the emulator's translation) lacks them; one such core is enough to refuse.
        val mixed = modern + "\nprocessor : 2\nFeatures : fp asimd evtstrm aes pmull sha1 sha2 crc32"
        assertFalse(PhononCpu.hasArmFeatures(mixed))
        assertFalse(PhononCpu.hasArmFeatures(""))
        assertFalse(PhononCpu.hasArmFeatures("flags : fpu vme de pse sse sse2 avx2"))
    }
}
