import { MD3DarkTheme } from "react-native-paper";
import type { AppTheme } from "../types";

export const midnightTheme: AppTheme = {
  ...MD3DarkTheme,
  id: "midnight",
  name: "Midnight",
  description: "Cool blue-dark theme with sharp edges",
  icon: "weather-night",
  isDark: true,
  roundness: 4,
  colors: {
    ...MD3DarkTheme.colors,
    primary: "#58A6FF",
    onPrimary: "#0D1117",
    primaryContainer: "#1F6FEB",
    onPrimaryContainer: "#C6E6FF",

    secondary: "#BC8CFF",
    onSecondary: "#0D1117",
    secondaryContainer: "#6E40C9",
    onSecondaryContainer: "#DCC8FF",

    tertiary: "#3FB950",
    onTertiary: "#0D1117",
    tertiaryContainer: "#238636",
    onTertiaryContainer: "#AFF5B4",

    error: "#FF7B72",
    onError: "#0D1117",
    errorContainer: "#DA3633",
    onErrorContainer: "#FFDAD6",

    background: "#0D1117",
    onBackground: "#C9D1D9",

    surface: "#161B22",
    onSurface: "#C9D1D9",
    surfaceVariant: "#21262D",
    onSurfaceVariant: "#8B949E",

    outline: "#30363D",
    outlineVariant: "#484F58",

    shadow: "#000000",
    scrim: "#000000",

    inverseSurface: "#C9D1D9",
    inverseOnSurface: "#161B22",
    inversePrimary: "#1B3A5F",

    elevation: {
      level0: "transparent",
      level1: "#161B22",
      level2: "#1C2129",
      level3: "#21262D",
      level4: "#262C34",
      level5: "#2B323B",
    },

    surfaceDisabled: "rgba(201, 209, 217, 0.12)",
    onSurfaceDisabled: "rgba(201, 209, 217, 0.38)",
    backdrop: "rgba(0, 0, 0, 0.6)",
  },
  typography: {
    fontFamily: "sans-serif",
    headingFontFamily: "sans-serif",
    monoFontFamily: "monospace",
    readerFontFamily: "monospace",
    readerLineHeight: 1.6,
  },
  shapes: {
    cardRadius: 8,
    buttonRadius: 4,
    dialogRadius: 8,
    fabRadius: 8,
    chipRadius: 4,
    searchBarRadius: 4,
    elevationStyle: "border",
  },
  buttonDefaults: {
    mode: "outlined",
    textTransform: "none",
    borderWidth: 1.5,
    buttonHeight: 40,
  },
}
