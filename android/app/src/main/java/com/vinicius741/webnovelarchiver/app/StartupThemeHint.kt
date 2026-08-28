package com.vinicius741.webnovelarchiver.app

import android.content.Context

// The user's theme lives in the JSON storage that repository hydration loads seconds into a cold
// start (and the whole-file read is exactly what the 2026-08-26 performance audit flagged). This
// tiny SharedPreferences hint lets the very first frame paint the right theme background — the
// branded startup state — without waiting for that hydration. MainActivity rewrites it from the
// authoritative DisplayPreferences on every launch, so drift self-heals within one start.

private const val STARTUP_PREFERENCES = "startup"
private const val KEY_THEME_ID = "theme_id"

internal object StartupThemeHint {
    fun read(context: Context): String? =
        context
            .getSharedPreferences(STARTUP_PREFERENCES, Context.MODE_PRIVATE)
            .getString(KEY_THEME_ID, null)

    fun write(
        context: Context,
        themeId: String,
    ) {
        context
            .getSharedPreferences(STARTUP_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME_ID, themeId)
            .apply()
    }
}
