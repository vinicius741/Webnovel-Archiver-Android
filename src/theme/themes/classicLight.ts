import { MD3LightTheme } from "react-native-paper";
import type { AppTheme } from "../types";

export const classicLightTheme: AppTheme = {
  ...MD3LightTheme,
  id: "classic-light",
  name: "Classic Light",
  description: "Warm cream light theme with navy accents",
  icon: "white-balance-sunny",
  isDark: false,
  roundness: 8,
  colors: {
    ...MD3LightTheme.colors,
    primary: "#1B3A5F",
    onPrimary: "#FFFFFF",
    primaryContainer: "#D4E4F7",
    onPrimaryContainer: "#001D3D",

    secondary: "#A65D3C",
    onSecondary: "#FFFFFF",
    secondaryContainer: "#F5DED3",
    onSecondaryContainer: "#3D1808",

    tertiary: "#8B6914",
    onTertiary: "#FFFFFF",
    tertiaryContainer: "#F2E4C4",
    onTertiaryContainer: "#261A00",

    error: "#BA1A1A",
    onError: "#FFFFFF",
    errorContainer: "#FFDAD6",
    onErrorContainer: "#410002",

    background: "#F7F3ED",
    onBackground: "#1E1C1A",

    surface: "#FFFDF9",
    onSurface: "#1E1C1A",
    surfaceVariant: "#EDE8E0",
    onSurfaceVariant: "#4D4944",

    outline: "#7E766E",
    outlineVariant: "#CFC8BF",

    shadow: "#000000",
    scrim: "#000000",

    inverseSurface: "#33302C",
    inverseOnSurface: "#F5F0EB",
    inversePrimary: "#A6C8E8",

    elevation: {
      level0: "#FFFDF9",
      level1: "#F5F0EB",
      level2: "#F0EBE5",
      level3: "#EBE6DF",
      level4: "#E8E3DC",
      level5: "#E3DED7",
    },

    surfaceDisabled: "rgba(30, 28, 26, 0.12)",
    onSurfaceDisabled: "rgba(30, 28, 26, 0.38)",
    backdrop: "rgba(51, 48, 44, 0.4)",
  },
  typography: {
    fontFamily: "sans-serif",
    headingFontFamily: "sans-serif",
    monoFontFamily: "monospace",
    readerFontFamily: "serif",
    readerLineHeight: 1.7,
  },
  shapes: {
    cardRadius: 12,
    buttonRadius: 8,
    dialogRadius: 16,
    fabRadius: 16,
    chipRadius: 8,
    searchBarRadius: 8,
    elevationStyle: "shadow",
  },
  buttonDefaults: {
    mode: "contained-tonal",
    textTransform: "none",
    borderWidth: 0,
    buttonHeight: 42,
  },
}
