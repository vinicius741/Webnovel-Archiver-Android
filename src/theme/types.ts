import type { MD3Theme } from "react-native-paper";

export type ThemeId = string;

export type ThemeMode = "system" | "light" | "dark";

export interface ThemeTypography {
  fontFamily: string;
  headingFontFamily: string;
  monoFontFamily: string;
  readerFontFamily: string;
  readerLineHeight: number;
}

export interface ThemeShapes {
  cardRadius: number;
  buttonRadius: number;
  dialogRadius: number;
  fabRadius: number;
  chipRadius: number;
  searchBarRadius: number;
  elevationStyle: "shadow" | "border" | "none";
}

export interface ThemeButtonDefaults {
  mode: "contained" | "outlined" | "contained-tonal" | "elevated" | "text";
  textTransform: "uppercase" | "none";
  borderWidth: number;
  buttonHeight: number;
}

export interface AppTheme extends MD3Theme {
  id: ThemeId;
  name: string;
  description: string;
  icon: string;
  isDark: boolean;
  typography: ThemeTypography;
  shapes: ThemeShapes;
  buttonDefaults: ThemeButtonDefaults;
}
