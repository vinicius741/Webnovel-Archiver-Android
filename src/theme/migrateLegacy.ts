import AsyncStorage from "@react-native-async-storage/async-storage";
import { STORAGE_KEYS } from "../services/storage/storageKeys";

const LEGACY_THEME_KEY = "wa_theme_mode";
const LEGACY_VARIANT_KEY = "wa_theme_variant_v1";

export async function migrateLegacyTheme(): Promise<void> {
  try {
    // If the new key already exists, nothing to migrate
    const existing = await AsyncStorage.getItem(STORAGE_KEYS.THEME_ACTIVE);
    if (existing !== null) return;

    // Try intermediate format: single variant key
    const oldVariant = await AsyncStorage.getItem(LEGACY_VARIANT_KEY);
    if (oldVariant !== null) {
      await AsyncStorage.setItem(STORAGE_KEYS.THEME_ACTIVE, oldVariant);
      await AsyncStorage.removeItem(LEGACY_VARIANT_KEY);
      await cleanupOldKeys();
      return;
    }

    // Try dark/light variant pair from the three-key format
    const [savedDark, savedLight, savedMode] = await Promise.all([
      AsyncStorage.getItem(STORAGE_KEYS.THEME_DARK_VARIANT),
      AsyncStorage.getItem(STORAGE_KEYS.THEME_LIGHT_VARIANT),
      AsyncStorage.getItem(STORAGE_KEYS.THEME_MODE),
    ]);

    if (savedDark || savedLight) {
      let resolved: string | null = null;

      if (savedMode === "light" && savedLight) {
        resolved = savedLight;
      } else if (savedMode === "dark" && savedDark) {
        resolved = savedDark;
      } else if (savedDark && savedLight) {
        // "system" mode or boolean fallback — pick dark as safe default
        resolved = savedDark;
      } else {
        resolved = savedDark ?? savedLight;
      }

      if (resolved) {
        await AsyncStorage.setItem(STORAGE_KEYS.THEME_ACTIVE, resolved);
        await cleanupOldKeys();
        return;
      }
    }

    // Try oldest format: "wa_theme_mode" (light/dark/system)
    const legacy = await AsyncStorage.getItem(LEGACY_THEME_KEY);
    if (legacy !== null) {
      const id = legacy === "light" ? "classic-light" : "obsidian";
      await AsyncStorage.setItem(STORAGE_KEYS.THEME_ACTIVE, id);
      await AsyncStorage.removeItem(LEGACY_THEME_KEY);
      await cleanupOldKeys();
    }
  } catch (e) {
    console.error("Failed to migrate legacy theme preference", e);
  }
}

async function cleanupOldKeys(): Promise<void> {
  await Promise.all([
    AsyncStorage.removeItem(STORAGE_KEYS.THEME_DARK_VARIANT),
    AsyncStorage.removeItem(STORAGE_KEYS.THEME_LIGHT_VARIANT),
    AsyncStorage.removeItem(STORAGE_KEYS.THEME_MODE),
    AsyncStorage.removeItem(STORAGE_KEYS.THEME_FOLLOW_SYSTEM),
    AsyncStorage.removeItem(LEGACY_THEME_KEY),
    AsyncStorage.removeItem(LEGACY_VARIANT_KEY),
  ]);
}
