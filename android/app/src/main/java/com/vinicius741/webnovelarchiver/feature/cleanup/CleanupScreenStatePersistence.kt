package com.vinicius741.webnovelarchiver.feature.cleanup

import android.os.Bundle
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenModelStore

/**
 * Persists the Cleanup screen's view memory ([CleanupSavedState]: mode, sentence draft, scroll per
 * mode) across process death, alongside the route stack in MainActivity's saved state. The
 * screen's model takes it when it is first created.
 */
internal object CleanupScreenStatePersistence {
    private const val MODE = "cleanup.mode"
    private const val DRAFT = "cleanup.draft"
    private const val SCROLL_PREFIX = "cleanup.scroll."
    private val modelKey = AppRoute.CleanupRules.stableKey

    /** Saves the live model's memory, or the restored memory no model has taken yet. */
    fun save(
        models: ScreenModelStore,
        outState: Bundle,
    ) {
        val state = models.find<CleanupScreenModel>(modelKey)?.savedState() ?: models.peekRestored<CleanupSavedState>(modelKey) ?: return
        outState.putString(MODE, state.mode.name)
        outState.putString(DRAFT, state.sentenceDraft)
        CleanupMode.entries.forEach { outState.putInt(SCROLL_PREFIX + it.name, state.scroll[it] ?: 0) }
    }

    fun restore(
        models: ScreenModelStore,
        saved: Bundle,
    ) {
        val mode = saved.getString(MODE) ?: return
        val state =
            CleanupSavedState(
                mode = CleanupMode.entries.firstOrNull { it.name == mode } ?: CleanupMode.SENTENCES,
                sentenceDraft = saved.getString(DRAFT).orEmpty(),
                scroll = CleanupMode.entries.associateWith { saved.getInt(SCROLL_PREFIX + it.name) },
            )
        models.restore(modelKey, state)
    }
}
