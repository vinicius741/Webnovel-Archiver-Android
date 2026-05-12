import React from "react";
import { View, StyleSheet } from "react-native";
import { Text, RadioButton, List, useTheme } from "react-native-paper";
import { useThemeContext } from "../../theme/ThemeContext";
import { getAllThemes } from "../../theme/registry";
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
  const { activeThemeId, setTheme } = useThemeContext();

  const themes = getAllThemes();

  return (
    <View>
      <Text variant="labelLarge" style={styles.sectionHeader}>
        Theme
      </Text>
      {themes.map((t) => (
        <ThemeCard
          key={t.id}
          theme={t}
          isActive={activeThemeId === t.id}
          onSelect={() => void setTheme(t.id)}
        />
      ))}
    </View>
  );
}

const styles = StyleSheet.create({
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
