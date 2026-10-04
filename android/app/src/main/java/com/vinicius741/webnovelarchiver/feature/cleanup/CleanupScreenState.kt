package com.vinicius741.webnovelarchiver.feature.cleanup

import android.os.Bundle

enum class CleanupMode(
    val label: String,
) {
    SENTENCES("Full sentences"),
    REGEX("Regex rules"),
}

/** Tab choice, sentence draft and independent scroll positions survive screen rebuilds. */
class CleanupScreenState {
    var mode = CleanupMode.SENTENCES
    var sentenceDraft = ""
    val scrollPositions = mutableMapOf<CleanupMode, Int>()

    fun save(outState: Bundle) {
        outState.putString("cleanup.mode", mode.name)
        outState.putString("cleanup.draft", sentenceDraft)
        CleanupMode.values().forEach { outState.putInt("cleanup.scroll.${it.name}", scrollPositions[it] ?: 0) }
    }

    fun restore(saved: Bundle) {
        mode = CleanupMode.values().firstOrNull { it.name == saved.getString("cleanup.mode") } ?: CleanupMode.SENTENCES
        sentenceDraft = saved.getString("cleanup.draft").orEmpty()
        CleanupMode.values().forEach { scrollPositions[it] = saved.getInt("cleanup.scroll.${it.name}") }
    }
}
