package com.vinicius741.webnovelarchiver.ui

import android.graphics.Color
import com.vinicius741.webnovelarchiver.app.MainActivity

/**
 * Material Design 3 color tokens for a single theme. Values mirror the legacy
 * React Native themes in `src/theme/themes` so the native app matches the
 * original look (Obsidian, Midnight, Forest, Classic Light).
 */
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

enum class ElevationStyle { SHADOW, BORDER, NONE }

/** Shape / corner-radius tokens for a theme. */
data class ThemeShapes(
    val cardRadius: Int,
    val buttonRadius: Int,
    val dialogRadius: Int,
    val chipRadius: Int,
    val searchRadius: Int,
    val elevationStyle: ElevationStyle,
)

data class AppTheme(
    val id: String,
    val name: String,
    val description: String,
    val isDark: Boolean,
    val colors: ThemeColors,
    val shapes: ThemeShapes,
) {
    /** A dimmed version of the background used for the window scrim / shadow tint. */
    val shadowColor: Int get() = Color.argb(120, 0, 0, 0)
}

/** The four legacy themes, ported 1:1 from the React Native definitions. */
object Themes {
    private fun c(hex: String): Int = Color.parseColor(hex)

    val obsidian =
        AppTheme(
            id = "obsidian",
            name = "Obsidian",
            description = "Warm dark theme with gold accents",
            isDark = true,
            colors =
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
                ),
            shapes = ThemeShapes(12, 8, 16, 8, 8, ElevationStyle.SHADOW),
        )

    val midnight =
        AppTheme(
            id = "midnight",
            name = "Midnight",
            description = "Cool blue-dark theme with sharp edges",
            isDark = true,
            colors =
                ThemeColors(
                    primary = c("#58A6FF"),
                    onPrimary = c("#0D1117"),
                    primaryContainer = c("#1F6FEB"),
                    onPrimaryContainer = c("#C6E6FF"),
                    secondary = c("#BC8CFF"),
                    onSecondary = c("#0D1117"),
                    secondaryContainer = c("#6E40C9"),
                    onSecondaryContainer = c("#DCC8FF"),
                    tertiary = c("#3FB950"),
                    onTertiary = c("#0D1117"),
                    tertiaryContainer = c("#238636"),
                    onTertiaryContainer = c("#AFF5B4"),
                    error = c("#FF7B72"),
                    onError = c("#0D1117"),
                    errorContainer = c("#DA3633"),
                    onErrorContainer = c("#FFDAD6"),
                    background = c("#0D1117"),
                    onBackground = c("#C9D1D9"),
                    surface = c("#161B22"),
                    onSurface = c("#C9D1D9"),
                    surfaceVariant = c("#21262D"),
                    onSurfaceVariant = c("#8B949E"),
                    outline = c("#30363D"),
                    outlineVariant = c("#484F58"),
                    elevation1 = c("#161B22"),
                    elevation2 = c("#1C2129"),
                    elevation3 = c("#21262D"),
                    elevation4 = c("#262C34"),
                    elevation5 = c("#2B323B"),
                ),
            shapes = ThemeShapes(8, 4, 8, 4, 4, ElevationStyle.BORDER),
        )

    val forest =
        AppTheme(
            id = "forest",
            name = "Forest",
            description = "Natural dark theme with soft green tones",
            isDark = true,
            colors =
                ThemeColors(
                    primary = c("#7CB69D"),
                    onPrimary = c("#0D1A0F"),
                    primaryContainer = c("#4A8B6F"),
                    onPrimaryContainer = c("#B8E4D0"),
                    secondary = c("#C4A882"),
                    onSecondary = c("#1A1206"),
                    secondaryContainer = c("#8B734F"),
                    onSecondaryContainer = c("#E6D4B8"),
                    tertiary = c("#A0C4B8"),
                    onTertiary = c("#0D1A14"),
                    tertiaryContainer = c("#6A9488"),
                    onTertiaryContainer = c("#C8E6DC"),
                    error = c("#FFB4AB"),
                    onError = c("#690005"),
                    errorContainer = c("#93000A"),
                    onErrorContainer = c("#FFDAD6"),
                    background = c("#0D1A0F"),
                    onBackground = c("#DDE8D5"),
                    surface = c("#1A2B1D"),
                    onSurface = c("#DDE8D5"),
                    surfaceVariant = c("#243328"),
                    onSurfaceVariant = c("#A3B49E"),
                    outline = c("#3A4D3E"),
                    outlineVariant = c("#4D6352"),
                    elevation1 = c("#1A2B1D"),
                    elevation2 = c("#1F3122"),
                    elevation3 = c("#243328"),
                    elevation4 = c("#29382D"),
                    elevation5 = c("#2F3D33"),
                ),
            shapes = ThemeShapes(16, 12, 20, 10, 12, ElevationStyle.SHADOW),
        )

    val classicLight =
        AppTheme(
            id = "classic-light",
            name = "Classic Light",
            description = "Warm cream light theme with navy accents",
            isDark = false,
            colors =
                ThemeColors(
                    primary = c("#1B3A5F"),
                    onPrimary = c("#FFFFFF"),
                    primaryContainer = c("#D4E4F7"),
                    onPrimaryContainer = c("#001D3D"),
                    secondary = c("#A65D3C"),
                    onSecondary = c("#FFFFFF"),
                    secondaryContainer = c("#F5DED3"),
                    onSecondaryContainer = c("#3D1808"),
                    tertiary = c("#8B6914"),
                    onTertiary = c("#FFFFFF"),
                    tertiaryContainer = c("#F2E4C4"),
                    onTertiaryContainer = c("#261A00"),
                    error = c("#BA1A1A"),
                    onError = c("#FFFFFF"),
                    errorContainer = c("#FFDAD6"),
                    onErrorContainer = c("#410002"),
                    background = c("#F7F3ED"),
                    onBackground = c("#1E1C1A"),
                    surface = c("#FFFDF9"),
                    onSurface = c("#1E1C1A"),
                    surfaceVariant = c("#EDE8E0"),
                    onSurfaceVariant = c("#4D4944"),
                    outline = c("#7E766E"),
                    outlineVariant = c("#CFC8BF"),
                    elevation1 = c("#F5F0EB"),
                    elevation2 = c("#F0EBE5"),
                    elevation3 = c("#EBE6DF"),
                    elevation4 = c("#E8E3DC"),
                    elevation5 = c("#E3DED7"),
                ),
            shapes = ThemeShapes(12, 8, 16, 8, 8, ElevationStyle.SHADOW),
        )

    val all: List<AppTheme> = listOf(obsidian, midnight, forest, classicLight)

    fun byId(id: String): AppTheme = all.firstOrNull { it.id == id } ?: obsidian
}

/**
 * Holds the active theme for the process. Set once in [MainActivity.onCreate]
 * (and again whenever the user picks a new theme) and read by every view builder
 * in [Ui]. Because the app uses programmatic views with no XML theming, this is
 * the single source of truth for colors and shapes.
 */
object ThemeManager {
    @Volatile var current: AppTheme = Themes.obsidian
        private set

    fun apply(themeId: String) {
        current = Themes.byId(themeId)
    }

    val colors: ThemeColors get() = current.colors
    val shapes: ThemeShapes get() = current.shapes
}
