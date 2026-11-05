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

    val colors: ThemeColors =
        ThemeColors(
            primary = c("#C9A84C"),
            onPrimary = c("#3D2E00"),
            primaryContainer = c("#574400"),
            onPrimaryContainer = c("#E8D597"),
            secondary = c("#D4A88C"),
            onSecondary = c("#4D280F"),
            secondaryContainer = c("#663C21"),
            onSecondaryContainer = c("#F5DED3"),
            tertiary = c("#D4B85C"),
            onTertiary = c("#3D3000"),
            tertiaryContainer = c("#574800"),
            onTertiaryContainer = c("#F2E4C4"),
            error = c("#FFB4AB"),
            onError = c("#690005"),
            errorContainer = c("#93000A"),
            onErrorContainer = c("#FFDAD6"),
            background = c("#12110F"),
            onBackground = c("#E8E2DA"),
            surface = c("#1C1A17"),
            onSurface = c("#E8E2DA"),
            surfaceVariant = c("#252320"),
            onSurfaceVariant = c("#B0A99A"),
            outline = c("#3D3A36"),
            outlineVariant = c("#4D4944"),
            elevation1 = c("#1C1A17"),
            elevation2 = c("#201E1B"),
            elevation3 = c("#252320"),
            elevation4 = c("#2A2725"),
            elevation5 = c("#302D2A"),
        )

    val shapes: ThemeShapes = ThemeShapes(cardRadius = 12, buttonRadius = 8, dialogRadius = 16, chipRadius = 8, searchRadius = 8)

    /** Tint used for window scrims and elevation shadows. */
    val shadowColor: Int = Color.argb(120, 0, 0, 0)
}
