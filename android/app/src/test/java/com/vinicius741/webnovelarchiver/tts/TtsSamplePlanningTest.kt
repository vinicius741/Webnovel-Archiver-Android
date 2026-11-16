package com.vinicius741.webnovelarchiver.tts

import com.vinicius741.webnovelarchiver.domain.model.TtsSettings
import com.vinicius741.webnovelarchiver.narration.NarrationServer
import com.vinicius741.webnovelarchiver.tts.kokoro.BuiltInModel
import com.vinicius741.webnovelarchiver.tts.kokoro.KokoroVoices
import org.junit.Assert.assertEquals
import org.junit.Test

class TtsSamplePlanningTest {
    private val bazzite = NarrationServer(id = "b", name = "Bazzite")
    private val mac = NarrationServer(id = "m", name = "MacBook")
    private val kokoro = TtsSettings(enginePackage = BuiltInModel.KOKORO_V1.engineId)

    @Test
    fun `an Android engine or a model on the phone plays on the phone`() {
        assertEquals(
            TtsSamplePlace.Phone,
            TtsSamplePlanning.place(TtsSettings(enginePackage = "com.google.android.tts"), false, listOf(mac)),
        )
        assertEquals(TtsSamplePlace.Phone, TtsSamplePlanning.place(kokoro, true, listOf(mac)))
    }

    @Test
    fun `a model missing from the phone generates on the computers, never a stand-in`() {
        val place = TtsSamplePlanning.place(kokoro, false, listOf(bazzite, mac))
        assertEquals(TtsSamplePlace.Computers(listOf(bazzite, mac), BuiltInModel.KOKORO_V1.defaultVoice), place)
    }

    @Test
    fun `the chosen voice is the one sent`() {
        val voice = BuiltInModel.KOKORO_V1.voices.last()
        val settings = kokoro.copy(voiceIdentifier = KokoroVoices.identifier(voice))
        assertEquals(TtsSamplePlace.Computers(listOf(mac), voice), TtsSamplePlanning.place(settings, false, listOf(mac)))
    }

    @Test
    fun `a voice from another model falls back to the model's default`() {
        val other = BuiltInModel.KOKORO_V019.voices.first()
        val settings = kokoro.copy(voiceIdentifier = KokoroVoices.identifier(other))
        assertEquals(
            TtsSamplePlace.Computers(listOf(mac), BuiltInModel.KOKORO_V1.defaultVoice),
            TtsSamplePlanning.place(settings, false, listOf(mac)),
        )
    }

    @Test
    fun `without a computer the sample says why instead of using another voice`() {
        assertEquals(
            TtsSamplePlace.Unavailable(TtsSampleFailure.NoComputer(BuiltInModel.KOKORO_V1.family)),
            TtsSamplePlanning.place(kokoro, false, emptyList()),
        )
    }

    @Test
    fun `the pick defaults to Listen's computers, then all of them`() {
        val servers = listOf(bazzite, mac)
        assertEquals(listOf(mac), TtsSamplePlanning.chosenComputers(servers, null, listOf("m")))
        assertEquals(servers, TtsSamplePlanning.chosenComputers(servers, null, emptyList()))
        assertEquals(listOf(bazzite), TtsSamplePlanning.chosenComputers(servers, setOf("b"), listOf("m")))
        // A pick naming only removed computers falls back.
        assertEquals(listOf(mac), TtsSamplePlanning.chosenComputers(servers, setOf("gone"), listOf("m")))
    }

    @Test
    fun `chips toggle but keep one computer chosen`() {
        assertEquals(setOf("b", "m"), TtsSamplePlanning.toggle(listOf(bazzite), "m"))
        assertEquals(setOf("m"), TtsSamplePlanning.toggle(listOf(bazzite, mac), "b"))
        assertEquals(setOf("b"), TtsSamplePlanning.toggle(listOf(bazzite), "b"))
    }

    @Test
    fun `progress names the computers and counts sentences`() {
        assertEquals(
            "Generating on Bazzite + MacBook… 2 of 5 sentences",
            TtsSamplePlanning.progressLine(TtsSampleState.Generating(2, 5, "Bazzite + MacBook")),
        )
    }
}
