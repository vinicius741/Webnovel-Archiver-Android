import React, {
  createContext,
  useContext,
  useState,
  useEffect,
  useRef,
  ReactNode,
} from "react";
import { StatusBar } from "react-native";
import AsyncStorage from "@react-native-async-storage/async-storage";
import { Provider as PaperProvider } from "react-native-paper";
import type { ThemeId, AppTheme } from "./types";
import { getThemeById, getDefaultDark } from "./registry";
import { migrateLegacyTheme } from "./migrateLegacy";
import { STORAGE_KEYS } from "../services/storage/storageKeys";
import "./registerAll";

interface ThemeContextType {
  activeThemeId: ThemeId;
  setTheme: (id: ThemeId) => Promise<void>;
  isDark: boolean;
  theme: AppTheme;
}

const ThemeContext = createContext<ThemeContextType>({
  activeThemeId: "obsidian",
  setTheme: async () => {},
  isDark: true,
  theme: getDefaultDark(),
});

export const useThemeContext = () => useContext(ThemeContext);

const THEME_KEY = STORAGE_KEYS.THEME_ACTIVE;

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [activeThemeId, setActiveThemeId] = useState<ThemeId>("obsidian");
  const [isReady, setIsReady] = useState(false);
  const migrationDone = useRef(false);

  const theme = getThemeById(activeThemeId) ?? getDefaultDark();
  const isDark = theme.isDark;

  useEffect(() => {
    if (migrationDone.current) return;
    migrationDone.current = true;

    void (async () => {
      try {
        await migrateLegacyTheme();

        const saved = await AsyncStorage.getItem(THEME_KEY);
        if (saved && getThemeById(saved)) {
          setActiveThemeId(saved);
        }
      } catch (e) {
        console.error("Failed to load theme preference", e);
      } finally {
        setIsReady(true);
      }
    })();
  }, []);

  const setTheme = async (id: ThemeId) => {
    if (!getThemeById(id)) return;
    setActiveThemeId(id);
    try {
      await AsyncStorage.setItem(THEME_KEY, id);
    } catch (e) {
      console.error("Failed to save theme preference", e);
    }
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
      value={{ activeThemeId, setTheme, isDark, theme }}
    >
      <PaperProvider theme={theme}>{children}</PaperProvider>
    </ThemeContext.Provider>
  );
}
