import { obsidianTheme } from "../themes/obsidian";
import { midnightTheme } from "../themes/midnight";
import { classicLightTheme } from "../themes/classicLight";
import { getAllThemes, getDarkThemes, getLightThemes, getThemeById, getDefaultDark, getDefaultLight } from "../registry";
import "../registerAll";

describe("Theme Registry", () => {
  it("should have all themes registered", () => {
    expect(getAllThemes().length).toBe(4);
  });

  it("should return correct dark themes", () => {
    const dark = getDarkThemes();
    expect(dark.length).toBe(3);
    expect(dark.every(t => t.isDark)).toBe(true);
  });

  it("should return correct light themes", () => {
    const light = getLightThemes();
    expect(light.length).toBe(1);
    expect(light.every(t => !t.isDark)).toBe(true);
  });

  it("should get theme by id", () => {
    expect(getThemeById("obsidian")?.name).toBe("Obsidian");
    expect(getThemeById("midnight")?.name).toBe("Midnight");
    expect(getThemeById("forest")?.name).toBe("Forest");
    expect(getThemeById("classic-light")?.name).toBe("Classic Light");
    expect(getThemeById("nonexistent")).toBeUndefined();
  });

  it("should return default dark theme", () => {
    expect(getDefaultDark().id).toBe("obsidian");
  });

  it("should return default light theme", () => {
    expect(getDefaultLight().id).toBe("classic-light");
  });
});

describe("Theme Definitions", () => {
  it("obsidian theme has correct properties", () => {
    expect(obsidianTheme.id).toBe("obsidian");
    expect(obsidianTheme.isDark).toBe(true);
    expect(obsidianTheme.typography.readerFontFamily).toBe("serif");
    expect(obsidianTheme.shapes.cardRadius).toBe(12);
    expect(obsidianTheme.buttonDefaults.mode).toBe("contained-tonal");
    expect(obsidianTheme.colors.primary).toBe("#C9A84C");
  });

  it("midnight theme has correct properties", () => {
    expect(midnightTheme.id).toBe("midnight");
    expect(midnightTheme.isDark).toBe(true);
    expect(midnightTheme.typography.readerFontFamily).toBe("monospace");
    expect(midnightTheme.shapes.elevationStyle).toBe("border");
    expect(midnightTheme.buttonDefaults.mode).toBe("outlined");
    expect(midnightTheme.colors.primary).toBe("#58A6FF");
  });

  it("classic light theme has correct properties", () => {
    expect(classicLightTheme.id).toBe("classic-light");
    expect(classicLightTheme.isDark).toBe(false);
    expect(classicLightTheme.colors.primary).toBe("#1B3A5F");
    expect(classicLightTheme.colors.background).toBe("#F7F3ED");
  });

  it("all themes have required MD3 color tokens", () => {
    const themes = getAllThemes();
    const requiredColorKeys = [
      "primary", "onPrimary", "primaryContainer", "onPrimaryContainer",
      "secondary", "onSecondary", "secondaryContainer", "onSecondaryContainer",
      "tertiary", "onTertiary", "tertiaryContainer", "onTertiaryContainer",
      "error", "onError", "errorContainer", "onErrorContainer",
      "background", "onBackground", "surface", "onSurface",
      "surfaceVariant", "onSurfaceVariant", "outline", "outlineVariant",
      "inverseSurface", "inverseOnSurface", "inversePrimary",
    ];

    for (const theme of themes) {
      for (const key of requiredColorKeys) {
        expect(theme.colors[key as keyof typeof theme.colors]).toBeDefined();
      }
    }
  });

  it("all themes have elevation levels", () => {
    const themes = getAllThemes();
    for (const theme of themes) {
      const elev = theme.colors.elevation as Record<string, string>;
      expect(elev.level0).toBeDefined();
      expect(elev.level1).toBeDefined();
      expect(elev.level5).toBeDefined();
    }
  });
});
