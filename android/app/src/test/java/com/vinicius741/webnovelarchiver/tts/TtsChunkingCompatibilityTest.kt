package com.vinicius741.webnovelarchiver.tts

import com.google.gson.Gson
import com.vinicius741.webnovelarchiver.domain.model.TtsSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class TtsChunkingCompatibilityTest {
    @Test
    fun legacyChunkSizeJsonStillDeserializes() {
        val settings =
            Gson().fromJson(
                """{"pitch":1.0,"rate":1.0,"voiceIdentifier":null,"chunkSize":120}""",
                TtsSettings::class.java,
            )

        // Gson ignores the retired property, so old settings remain readable without preserving a
        // dead field in the runtime model.
        assertEquals(TtsSettings(), settings)
    }
}
