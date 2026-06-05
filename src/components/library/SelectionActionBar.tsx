import React from "react";
import { StyleSheet, View } from "react-native";
import { Text, useTheme, Surface, IconButton } from "react-native-paper";
import { useSafeAreaInsets } from "react-native-safe-area-context";

interface SelectionActionBarProps {
  selectedCount: number;
  onMove: () => void;
  onDelete: () => void;
  onCancel: () => void;
}

export const SelectionActionBar = ({
  selectedCount,
  onMove,
  onDelete,
  onCancel,
}: SelectionActionBarProps) => {
  const theme = useTheme();
  const insets = useSafeAreaInsets();
  const canAct = selectedCount > 0;

  return (
    <Surface
      style={[
        styles.container,
        { backgroundColor: theme.colors.surface, paddingBottom: insets.bottom },
      ]}
      elevation={4}
    >
      <View style={styles.content}>
        <Text variant="titleMedium" style={styles.countText}>
          {selectedCount} selected
        </Text>
        <View style={styles.actions}>
          <IconButton
            icon="close"
            mode="contained-tonal"
            onPress={onCancel}
            accessibilityLabel="Cancel selection"
          />
          <IconButton
            icon="trash-can-outline"
            mode="contained-tonal"
            iconColor={theme.colors.error}
            containerColor={theme.colors.errorContainer}
            disabled={!canAct}
            onPress={onDelete}
            accessibilityLabel="Delete selected"
          />
          <IconButton
            icon="folder-arrow-right-outline"
            mode="contained-tonal"
            disabled={!canAct}
            onPress={onMove}
            accessibilityLabel="Move selected"
          />
        </View>
      </View>
    </Surface>
  );
};

const styles = StyleSheet.create({
  container: {
    position: "absolute",
    bottom: 0,
    left: 0,
    right: 0,
    borderTopLeftRadius: 16,
    borderTopRightRadius: 16,
  },
  content: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "center",
    paddingHorizontal: 12,
    paddingVertical: 8,
    minHeight: 56,
  },
  countText: {
    flex: 1,
  },
  actions: {
    flexDirection: "row",
    gap: 4,
  },
});
