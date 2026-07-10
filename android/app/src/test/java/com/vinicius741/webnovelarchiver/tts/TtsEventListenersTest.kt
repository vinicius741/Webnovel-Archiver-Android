package com.vinicius741.webnovelarchiver.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class TtsEventListenersTest {
    @Test
    fun `listener registration is identity-idempotent and removable`() {
        val registry = TtsEventListeners()
        var calls = 0
        val listener: (TtsPlaybackSnapshot?) -> Unit = { calls += 1 }

        registry.addState(listener)
        registry.addState(listener)
        registry.dispatchState(null)
        registry.removeState(listener)
        registry.dispatchState(null)

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
