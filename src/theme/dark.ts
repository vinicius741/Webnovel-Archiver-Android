import { MD3DarkTheme as DefaultTheme } from "react-native-paper";

export const DarkTheme = {
  ...DefaultTheme,
  colors: {
    ...DefaultTheme.colors,
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

    surface: "#1A1917",
    onSurface: "#E8E2DA",
    surfaceVariant: "#2A2825",
    onSurfaceVariant: "#A8A29A",

    outline: "#7E766E",
    outlineVariant: "#4D4944",

    shadow: "#000000",
    scrim: "#000000",

    inverseSurface: "#E8E2DA",
    inverseOnSurface: "#1E1C1A",
    inversePrimary: "#1B3A5F",

    elevation: {
      level0: "#1A1917",
      level1: "#242320",
      level2: "#2E2C29",
      level3: "#383632",
      level4: "#3D3B37",
      level5: "#45433E",
    },

    surfaceDisabled: "rgba(232, 226, 218, 0.12)",
    onSurfaceDisabled: "rgba(232, 226, 218, 0.38)",
    backdrop: "rgba(0, 0, 0, 0.6)",
  },
};
