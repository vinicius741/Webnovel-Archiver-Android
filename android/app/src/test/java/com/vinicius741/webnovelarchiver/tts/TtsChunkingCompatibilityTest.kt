package com.vinicius741.webnovelarchiver.tts

import com.google.gson.Gson
import com.vinicius741.webnovelarchiver.cleanup.TtsTextPreparation
import com.vinicius741.webnovelarchiver.domain.model.TtsSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class TtsChunkingCompatibilityTest {
    @Test
    fun legacyChunkSizeJsonStillDeserializesWhilePreparationUsesOnePolicy() {
        val settings =
            Gson().fromJson(
                """{"pitch":1.0,"rate":1.0,"voiceIdentifier":null,"chunkSize":120}""",
                TtsSettings::class.java,
            )
        val html = "<p>First sentence. Second sentence.</p>"

        // Gson ignores the retired property, so old settings remain readable without preserving a
        // dead field in the runtime model.
        assertEquals(TtsSettings(), settings)
        assertEquals(
            TtsTextPreparation.prepareTtsChunks(html, emptyList()),
            TtsTextPreparation.prepareTtsChunks(html, emptyList()),
        )
    }
}
