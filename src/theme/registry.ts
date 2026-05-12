import type { AppTheme, ThemeId } from "./types";

interface RegistryEntry {
  theme: AppTheme;
}

const registry = new Map<ThemeId, RegistryEntry>();

export function registerTheme(theme: AppTheme): void {
  registry.set(theme.id, { theme });
}

export function getAllThemes(): AppTheme[] {
  return [...registry.values()].map((e) => e.theme);
}

export function getDarkThemes(): AppTheme[] {
  return getAllThemes().filter((t) => t.isDark);
}

export function getLightThemes(): AppTheme[] {
  return getAllThemes().filter((t) => !t.isDark);
}

export function getThemeById(id: ThemeId): AppTheme | undefined {
  return registry.get(id)?.theme;
}

export function getDefaultDark(): AppTheme {
  return getDarkThemes()[0];
}

export function getDefaultLight(): AppTheme {
  return getLightThemes()[0];
}
