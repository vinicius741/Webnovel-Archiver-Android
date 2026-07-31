package com.vinicius741.webnovelarchiver.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class TtsEventListenersTest {
    @Test
    fun `error listener registration is identity-idempotent and removable`() {
        val registry = TtsEventListeners()
        var calls = 0
        val listener: (TtsPlaybackError) -> Unit = { calls += 1 }

        registry.addError(listener)
        registry.addError(listener)
        registry.dispatchError(TtsPlaybackError(TtsPlaybackErrorKind.InitFailed))
        registry.removeError(listener)
        registry.dispatchError(TtsPlaybackError(TtsPlaybackErrorKind.InitFailed))

        assertEquals(1, calls)
    }

    @Test
    fun `listener may remove itself during dispatch`() {
        val registry = TtsEventListeners()
        val calls = mutableListOf<String>()
        lateinit var first: (TtsPlaybackError) -> Unit
        first = {
            calls += "first"
            registry.removeError(first)
        }
        val second: (TtsPlaybackError) -> Unit = { calls += "second" }
        registry.addError(first)
        registry.addError(second)

        registry.dispatchError(TtsPlaybackError(TtsPlaybackErrorKind.InitFailed))
        registry.dispatchError(TtsPlaybackError(TtsPlaybackErrorKind.InitFailed))

        assertEquals(listOf("first", "second", "second"), calls)
    }
}
