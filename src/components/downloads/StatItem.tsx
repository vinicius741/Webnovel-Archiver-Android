import React, { memo } from "react";
import { View, StyleSheet } from "react-native";
import { Text, IconButton, MD3Theme } from "react-native-paper";

interface Props {
  icon: string;
  value: number;
  label: string;
  color?: string;
  theme: MD3Theme;
}

export const StatItem = memo(({ icon, value, label, color, theme }: Props) => (
  <View style={styles.container}>
    <IconButton
      icon={icon}
      size={20}
      iconColor={color || theme.colors.onSurfaceVariant}
      style={styles.icon}
    />
    <Text
      variant="titleLarge"
      style={[styles.value, { color: color || theme.colors.onSurface }]}
    >
      {value}
    </Text>
    <Text
      variant="bodySmall"
      style={[styles.label, { color: theme.colors.onSurfaceVariant }]}
    >
      {label}
    </Text>
  </View>
));

const styles = StyleSheet.create({
  container: {
    alignItems: "center",
    flex: 1,
  },
  icon: {
    margin: 0,
    marginBottom: -4,
  },
  value: {
    fontWeight: "bold",
  },
  label: {
    marginTop: -2,
  },
});
