import AsyncStorage from "@react-native-async-storage/async-storage";
import { STORAGE_KEYS } from "../services/storage/storageKeys";

const LEGACY_THEME_KEY = "wa_theme_mode";
const LEGACY_VARIANT_KEY = "wa_theme_variant_v1";

export async function migrateLegacyTheme(): Promise<void> {
  try {
    // Migrate oldest format: "wa_theme_mode" (light/dark/system)
    const legacy = await AsyncStorage.getItem(LEGACY_THEME_KEY);
    if (legacy !== null) {
      switch (legacy) {
        case "dark":
          await Promise.all([
            AsyncStorage.setItem(STORAGE_KEYS.THEME_DARK_VARIANT, "obsidian"),
            AsyncStorage.setItem(
              STORAGE_KEYS.THEME_FOLLOW_SYSTEM,
              JSON.stringify(false),
            ),
          ]);
          break;
        case "light":
          await Promise.all([
            AsyncStorage.setItem(
              STORAGE_KEYS.THEME_LIGHT_VARIANT,
              "classic-light",
            ),
            AsyncStorage.setItem(
              STORAGE_KEYS.THEME_FOLLOW_SYSTEM,
              JSON.stringify(false),
            ),
          ]);
          break;
        case "system":
          await Promise.all([
            AsyncStorage.setItem(STORAGE_KEYS.THEME_DARK_VARIANT, "obsidian"),
            AsyncStorage.setItem(
              STORAGE_KEYS.THEME_LIGHT_VARIANT,
              "classic-light",
            ),
            AsyncStorage.setItem(
              STORAGE_KEYS.THEME_FOLLOW_SYSTEM,
              JSON.stringify(true),
            ),
          ]);
          break;
      }
      await AsyncStorage.removeItem(LEGACY_THEME_KEY);
    }

    // Migrate intermediate format: "wa_theme_variant_v1" (single variant key)
    const oldVariant = await AsyncStorage.getItem(LEGACY_VARIANT_KEY);
    if (oldVariant !== null) {
      // Determine which variant group it belongs to based on the theme id
      const knownDark = ["obsidian", "midnight", "forest"];
      const isDarkVariant = knownDark.includes(oldVariant);

      if (isDarkVariant) {
        await AsyncStorage.setItem(
          STORAGE_KEYS.THEME_DARK_VARIANT,
          oldVariant,
        );
      } else {
        await AsyncStorage.setItem(
          STORAGE_KEYS.THEME_LIGHT_VARIANT,
          oldVariant,
        );
      }
      await AsyncStorage.removeItem(LEGACY_VARIANT_KEY);
    }
  } catch (e) {
    console.error("Failed to migrate legacy theme preference", e);
  }
}
