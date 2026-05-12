import React from "react";
import { View, StyleSheet } from "react-native";
import { Text, RadioButton, Switch, List, useTheme } from "react-native-paper";
import { useThemeContext } from "../../theme/ThemeContext";
import { getDarkThemes, getLightThemes } from "../../theme/registry";
import type { AppTheme } from "../../theme/types";

function ThemeCard({
  theme,
  isActive,
  onSelect,
}: {
  theme: AppTheme;
  isActive: boolean;
  onSelect: () => void;
}) {
  const paperTheme = useTheme();

  return (
    <List.Item
      title={theme.name}
      description={theme.description}
      left={() => (
        <View style={styles.swatchContainer}>
          <View
            style={[
              styles.swatch,
              { backgroundColor: theme.colors.primary },
            ]}
          />
          <View
            style={[
              styles.swatch,
              { backgroundColor: theme.colors.surface },
            ]}
          />
        </View>
      )}
      right={() => (
        <RadioButton.Android
          value={theme.id}
          status={isActive ? "checked" : "unchecked"}
        />
      )}
      onPress={onSelect}
      style={[
        styles.themeCard,
        isActive && {
          backgroundColor: paperTheme.colors.primaryContainer,
          borderRadius: 8,
        },
      ]}
    />
  );
}

export function ThemePicker() {
  const {
    themeMode,
    setThemeMode,
    darkVariantId,
    lightVariantId,
    setDarkVariant,
    setLightVariant,
    selectDarkTheme,
    selectLightTheme,
    isDark,
  } = useThemeContext();

  const darkThemes = getDarkThemes();
  const lightThemes = getLightThemes();
  const followSystem = themeMode === "system";

  const handleDarkSelect = (id: string) => {
    if (followSystem) {
      void setDarkVariant(id);
    } else {
      void selectDarkTheme(id);
    }
  };

  const handleLightSelect = (id: string) => {
    if (followSystem) {
      void setLightVariant(id);
    } else {
      void selectLightTheme(id);
    }
  };

  const toggleFollowSystem = (value: boolean) => {
    if (value) {
      void setThemeMode("system");
    } else {
      void setThemeMode(isDark ? "dark" : "light");
    }
  };

  return (
    <View>
      <View style={styles.switchRow}>
        <Text variant="bodyMedium">Follow system appearance</Text>
        <Switch value={followSystem} onValueChange={toggleFollowSystem} />
      </View>

      {followSystem || isDark ? (
        <View style={styles.section}>
          <Text variant="labelLarge" style={styles.sectionHeader}>
            Dark Themes
          </Text>
          {darkThemes.map((t) => (
            <ThemeCard
              key={t.id}
              theme={t}
              isActive={darkVariantId === t.id}
              onSelect={() => handleDarkSelect(t.id)}
            />
          ))}
        </View>
      ) : null}

      {followSystem || !isDark ? (
        <View style={styles.section}>
          <Text variant="labelLarge" style={styles.sectionHeader}>
            Light Themes
          </Text>
          {lightThemes.map((t) => (
            <ThemeCard
              key={t.id}
              theme={t}
              isActive={lightVariantId === t.id}
              onSelect={() => handleLightSelect(t.id)}
            />
          ))}
        </View>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  switchRow: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "center",
    paddingHorizontal: 16,
    paddingVertical: 8,
  },
  section: {
    marginTop: 8,
  },
  sectionHeader: {
    paddingHorizontal: 16,
    paddingVertical: 8,
    opacity: 0.7,
  },
  themeCard: {
    borderRadius: 8,
  },
  swatchContainer: {
    flexDirection: "row",
    alignItems: "center",
    gap: 4,
    paddingRight: 8,
  },
  swatch: {
    width: 24,
    height: 24,
    borderRadius: 12,
    borderWidth: 1,
    borderColor: "rgba(128,128,128,0.3)",
  },
});
