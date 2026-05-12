import type { AppTheme } from "../theme/types";
import { MD3DarkTheme } from "react-native-paper";
import React, { ReactNode } from "react";
import { Provider as PaperProvider } from "react-native-paper";

export function createMockTheme(
  overrides?: Partial<AppTheme>,
): AppTheme {
  return {
    ...MD3DarkTheme,
    id: "test-theme",
    name: "Test Theme",
    description: "Mock",
    icon: "palette",
    isDark: true,
    colors: {
      ...MD3DarkTheme.colors,
      primary: "#C9A84C",
      onPrimary: "#3D2E00",
      surface: "#1C1A17",
      onSurface: "#E8E2DA",
      surfaceVariant: "#252320",
      onSurfaceVariant: "#B0A99A",
      background: "#12110F",
      onBackground: "#E8E2DA",
      outline: "#3D3A36",
      elevation: {
        level0: "transparent",
        level1: "#1C1A17",
        level2: "#201E1B",
        level3: "#252320",
        level4: "#2A2725",
        level5: "#302D2A",
      },
      ...(overrides?.colors ?? {}),
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
    roundness: 8,
    ...overrides,
  } as AppTheme;
}

export function MockThemeProvider({
  children,
  theme,
}: {
  children: ReactNode;
  theme?: AppTheme;
}) {
  return (
    <PaperProvider theme={theme ?? createMockTheme()}>
      {children}
    </PaperProvider>
  );
}
