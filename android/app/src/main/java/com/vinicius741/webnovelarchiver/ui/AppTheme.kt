package com.vinicius741.webnovelarchiver.ui

import android.graphics.Color

/** Material Design 3 color tokens for the app's single (Obsidian) theme. */
data class ThemeColors(
    val primary: Int,
    val onPrimary: Int,
    val primaryContainer: Int,
    val onPrimaryContainer: Int,
    val secondary: Int,
    val onSecondary: Int,
    val secondaryContainer: Int,
    val onSecondaryContainer: Int,
    val tertiary: Int,
    val onTertiary: Int,
    val tertiaryContainer: Int,
    val onTertiaryContainer: Int,
    val error: Int,
    val onError: Int,
    val errorContainer: Int,
    val onErrorContainer: Int,
    val background: Int,
    val onBackground: Int,
    val surface: Int,
    val onSurface: Int,
    val surfaceVariant: Int,
    val onSurfaceVariant: Int,
    val outline: Int,
    val outlineVariant: Int,
    val elevation1: Int,
    val elevation2: Int,
    val elevation3: Int,
    val elevation4: Int,
    val elevation5: Int,
    /** Tertiary text: timestamps, counts, disabled hints. */
    val faint: Int,
    /** The reading bookmark (ivory): reads on the gold progress bar without looking like an error. */
    val bookmark: Int,
    /** Hairline borders between list rows and around cards. */
    val line: Int,
    /** Bottom navigation / rail surface. */
    val navSurface: Int,
)

/** Shape / corner-radius tokens (dp). */
data class ThemeShapes(
    val cardRadius: Int,
    val buttonRadius: Int,
    val dialogRadius: Int,
    val chipRadius: Int,
    val searchRadius: Int,
)

/**
 * The app's one and only look: a warm dark "Obsidian" theme with gold accents. Because the app
 * uses programmatic views with no XML theming, this is the single source of truth for colors and
 * shapes, read by every view builder in [Ui].
 */
object ThemeManager {
    private fun c(hex: String): Int = Color.parseColor(hex)

    // v3 "Restructured" palette (2026-11 redesign): warm obsidian surfaces, gold primary,
    // copper secondary (Patreon, generation, late), sage tertiary (done / completed).
    val colors: ThemeColors =
        ThemeColors(
            primary = c("#D8B65A"),
            onPrimary = c("#2B2000"),
            primaryContainer = c("#47380C"),
            onPrimaryContainer = c("#F1E0A8"),
            secondary = c("#DCA582"),
            onSecondary = c("#3A1F0E"),
            secondaryContainer = c("#4B2E1D"),
            onSecondaryContainer = c("#F6DCCD"),
            tertiary = c("#B3C98D"),
            onTertiary = c("#1F2A0E"),
            tertiaryContainer = c("#2F3A20"),
            onTertiaryContainer = c("#D4E6B2"),
            error = c("#FF9A8F"),
            onError = c("#5A1B16"),
            errorContainer = c("#5A1B16"),
            onErrorContainer = c("#FFD9D4"),
            background = c("#110F0C"),
            onBackground = c("#EDE6DA"),
            surface = c("#191712"),
            onSurface = c("#EDE6DA"),
            surfaceVariant = c("#28241E"),
            onSurfaceVariant = c("#AAA190"),
            outline = c("#3A352D"),
            outlineVariant = c("#2A261F"),
            elevation1 = c("#191712"),
            elevation2 = c("#201D18"),
            elevation3 = c("#28241E"),
            elevation4 = c("#312C25"),
            elevation5 = c("#3A342B"),
            faint = c("#776F61"),
            bookmark = c("#F6EEDD"),
            line = c("#221F1A"),
            navSurface = c("#17150F"),
        )

    // Cards 18, pill buttons/chips/search, 28 sheets and dialogs.
    val shapes: ThemeShapes = ThemeShapes(cardRadius = 18, buttonRadius = 24, dialogRadius = 28, chipRadius = 18, searchRadius = 24)

    /** Tint used for window scrims and elevation shadows. */
    val shadowColor: Int = Color.argb(120, 0, 0, 0)
}
