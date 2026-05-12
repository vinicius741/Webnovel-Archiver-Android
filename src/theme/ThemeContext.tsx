import React, {
  createContext,
  useContext,
  useState,
  useEffect,
  useRef,
  ReactNode,
} from "react";
import { useColorScheme, StatusBar } from "react-native";
import AsyncStorage from "@react-native-async-storage/async-storage";
import { Provider as PaperProvider } from "react-native-paper";
import type { ThemeMode, ThemeId, AppTheme } from "./types";
import { getThemeById, getDefaultDark, getDefaultLight } from "./registry";
import { migrateLegacyTheme } from "./migrateLegacy";
import { STORAGE_KEYS } from "../services/storage/storageKeys";
import "./registerAll";

interface ThemeContextType {
  themeMode: ThemeMode;
  setThemeMode: (mode: ThemeMode) => Promise<void>;
  isDark: boolean;
  activeThemeId: ThemeId;
  setDarkVariant: (id: ThemeId) => Promise<void>;
  setLightVariant: (id: ThemeId) => Promise<void>;
  selectDarkTheme: (id: ThemeId) => Promise<void>;
  selectLightTheme: (id: ThemeId) => Promise<void>;
  darkVariantId: ThemeId;
  lightVariantId: ThemeId;
  theme: AppTheme;
}

const ThemeContext = createContext<ThemeContextType>({
  themeMode: "system",
  setThemeMode: async () => {},
  isDark: false,
  activeThemeId: "obsidian",
  setDarkVariant: async () => {},
  setLightVariant: async () => {},
  selectDarkTheme: async () => {},
  selectLightTheme: async () => {},
  darkVariantId: "obsidian",
  lightVariantId: "classic-light",
  theme: getDefaultDark(),
});

export const useThemeContext = () => useContext(ThemeContext);

async function persistAll(
  mode: ThemeMode,
  dId: ThemeId,
  lId: ThemeId,
): Promise<void> {
  try {
    await Promise.all([
      AsyncStorage.setItem(STORAGE_KEYS.THEME_DARK_VARIANT, dId),
      AsyncStorage.setItem(STORAGE_KEYS.THEME_LIGHT_VARIANT, lId),
      AsyncStorage.setItem(
        STORAGE_KEYS.THEME_FOLLOW_SYSTEM,
        JSON.stringify(mode === "system"),
      ),
    ]);
  } catch (e) {
    console.error("Failed to save theme preference", e);
  }
}

export function ThemeProvider({ children }: { children: ReactNode }) {
  const systemScheme = useColorScheme();
  const [themeMode, setThemeModeState] = useState<ThemeMode>("system");
  const [darkVariantId, setDarkVariantIdState] = useState<ThemeId>("obsidian");
  const [lightVariantId, setLightVariantIdState] =
    useState<ThemeId>("classic-light");
  const [isReady, setIsReady] = useState(false);
  const migrationDone = useRef(false);

  // Refs to track latest variant IDs for use in async closures
  const darkVariantRef = useRef(darkVariantId);
  const lightVariantRef = useRef(lightVariantId);
  const themeModeRef = useRef(themeMode);

  darkVariantRef.current = darkVariantId;
  lightVariantRef.current = lightVariantId;
  themeModeRef.current = themeMode;

  useEffect(() => {
    if (migrationDone.current) return;
    migrationDone.current = true;

    void (async () => {
      try {
        await migrateLegacyTheme();

        const [savedDark, savedLight, savedFollowSystem] = await Promise.all([
          AsyncStorage.getItem(STORAGE_KEYS.THEME_DARK_VARIANT),
          AsyncStorage.getItem(STORAGE_KEYS.THEME_LIGHT_VARIANT),
          AsyncStorage.getItem(STORAGE_KEYS.THEME_FOLLOW_SYSTEM),
        ]);

        if (savedDark) setDarkVariantIdState(savedDark);
        if (savedLight) setLightVariantIdState(savedLight);

        const followSystem = savedFollowSystem
          ? JSON.parse(savedFollowSystem)
          : false;

        if (followSystem) {
          setThemeModeState("system");
        } else if (savedDark) {
          setThemeModeState("dark");
        } else if (savedLight) {
          setThemeModeState("light");
        }
      } catch (e) {
        console.error("Failed to load theme preference", e);
      } finally {
        setIsReady(true);
      }
    })();
  }, []);

  const isDark =
    themeMode === "dark" ||
    (themeMode === "system" && systemScheme === "dark");

  const variantId = isDark ? darkVariantId : lightVariantId;
  const theme =
    getThemeById(variantId) ?? (isDark ? getDefaultDark() : getDefaultLight());

  const activeThemeId = theme.id;

  const setThemeMode = async (mode: ThemeMode) => {
    setThemeModeState(mode);
    await persistAll(mode, darkVariantRef.current, lightVariantRef.current);
  };

  const setDarkVariant = async (id: ThemeId) => {
    setDarkVariantIdState(id);
    await persistAll(themeModeRef.current, id, lightVariantRef.current);
  };

  const setLightVariant = async (id: ThemeId) => {
    setLightVariantIdState(id);
    await persistAll(themeModeRef.current, darkVariantRef.current, id);
  };

  // Atomic setters: change variant + mode in a single persist call
  const selectDarkTheme = async (id: ThemeId) => {
    setDarkVariantIdState(id);
    setThemeModeState("dark");
    await persistAll("dark", id, lightVariantRef.current);
  };

  const selectLightTheme = async (id: ThemeId) => {
    setLightVariantIdState(id);
    setThemeModeState("light");
    await persistAll("light", darkVariantRef.current, id);
  };

  useEffect(() => {
    StatusBar.setBackgroundColor(theme.colors.background);
    StatusBar.setBarStyle(isDark ? "light-content" : "dark-content");
  }, [theme.colors.background, isDark]);

  if (!isReady) {
    return null;
  }

  return (
    <ThemeContext.Provider
      value={{
        themeMode,
        setThemeMode,
        isDark,
        activeThemeId,
        setDarkVariant,
        setLightVariant,
        selectDarkTheme,
        selectLightTheme,
        darkVariantId,
        lightVariantId,
        theme,
      }}
    >
      <PaperProvider theme={theme}>{children}</PaperProvider>
    </ThemeContext.Provider>
  );
}
