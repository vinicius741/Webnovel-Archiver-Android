import { MD3DarkTheme } from "react-native-paper";
import type { AppTheme } from "../types";

export const forestTheme: AppTheme = {
  ...MD3DarkTheme,
  id: "forest",
  name: "Forest",
  description: "Natural dark theme with soft green tones",
  icon: "tree",
  isDark: true,
  roundness: 12,
  colors: {
    ...MD3DarkTheme.colors,
    primary: "#7CB69D",
    onPrimary: "#0D1A0F",
    primaryContainer: "#4A8B6F",
    onPrimaryContainer: "#B8E4D0",

    secondary: "#C4A882",
    onSecondary: "#1A1206",
    secondaryContainer: "#8B734F",
    onSecondaryContainer: "#E6D4B8",

    tertiary: "#A0C4B8",
    onTertiary: "#0D1A14",
    tertiaryContainer: "#6A9488",
    onTertiaryContainer: "#C8E6DC",

    error: "#FFB4AB",
    onError: "#690005",
    errorContainer: "#93000A",
    onErrorContainer: "#FFDAD6",

    background: "#0D1A0F",
    onBackground: "#DDE8D5",

    surface: "#1A2B1D",
    onSurface: "#DDE8D5",
    surfaceVariant: "#243328",
    onSurfaceVariant: "#A3B49E",

    outline: "#3A4D3E",
    outlineVariant: "#4D6352",

    shadow: "#000000",
    scrim: "#000000",

    inverseSurface: "#DDE8D5",
    inverseOnSurface: "#1A2B1D",
    inversePrimary: "#4A8B6F",

    elevation: {
      level0: "transparent",
      level1: "#1A2B1D",
      level2: "#1F3122",
      level3: "#243328",
      level4: "#29382D",
      level5: "#2F3D33",
    },

    surfaceDisabled: "rgba(221, 232, 213, 0.12)",
    onSurfaceDisabled: "rgba(221, 232, 213, 0.38)",
    backdrop: "rgba(0, 0, 0, 0.6)",
  },
  typography: {
    fontFamily: "sans-serif",
    headingFontFamily: "sans-serif",
    monoFontFamily: "monospace",
    readerFontFamily: "serif",
    readerLineHeight: 1.8,
  },
  shapes: {
    cardRadius: 16,
    buttonRadius: 12,
    dialogRadius: 20,
    fabRadius: 20,
    chipRadius: 10,
    searchBarRadius: 12,
    elevationStyle: "shadow",
  },
  buttonDefaults: {
    mode: "elevated",
    textTransform: "none",
    borderWidth: 0,
    buttonHeight: 44,
  },
}
