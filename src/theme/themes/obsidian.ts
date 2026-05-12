import { MD3DarkTheme } from "react-native-paper";
import type { AppTheme } from "../types";

export const obsidianTheme: AppTheme = {
  ...MD3DarkTheme,
  id: "obsidian",
  name: "Obsidian",
  description: "Warm dark theme with gold accents",
  icon: "moon-waning-crescent",
  isDark: true,
  roundness: 8,
  colors: {
    ...MD3DarkTheme.colors,
    primary: "#C9A84C",
    onPrimary: "#3D2E00",
    primaryContainer: "#574400",
    onPrimaryContainer: "#E8D597",

    secondary: "#D4A88C",
    onSecondary: "#4D280F",
    secondaryContainer: "#663C21",
    onSecondaryContainer: "#F5DED3",

    tertiary: "#D4B85C",
    onTertiary: "#3D3000",
    tertiaryContainer: "#574800",
    onTertiaryContainer: "#F2E4C4",

    error: "#FFB4AB",
    onError: "#690005",
    errorContainer: "#93000A",
    onErrorContainer: "#FFDAD6",

    background: "#12110F",
    onBackground: "#E8E2DA",

    surface: "#1C1A17",
    onSurface: "#E8E2DA",
    surfaceVariant: "#252320",
    onSurfaceVariant: "#B0A99A",

    outline: "#3D3A36",
    outlineVariant: "#4D4944",

    shadow: "#000000",
    scrim: "#000000",

    inverseSurface: "#E8E2DA",
    inverseOnSurface: "#1E1C1A",
    inversePrimary: "#A6C8E8",

    elevation: {
      level0: "transparent",
      level1: "#1C1A17",
      level2: "#201E1B",
      level3: "#252320",
      level4: "#2A2725",
      level5: "#302D2A",
    },

    surfaceDisabled: "rgba(232, 226, 218, 0.12)",
    onSurfaceDisabled: "rgba(232, 226, 218, 0.38)",
    backdrop: "rgba(0, 0, 0, 0.6)",
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
