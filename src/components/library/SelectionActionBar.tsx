import React from "react";
import { StyleSheet, View } from "react-native";
import { Text, Button, useTheme, Surface } from "react-native-paper";
import { useSafeAreaInsets } from "react-native-safe-area-context";

interface SelectionActionBarProps {
  selectedCount: number;
  onMove: () => void;
  onCancel: () => void;
}

export const SelectionActionBar = ({
  selectedCount,
  onMove,
  onCancel,
}: SelectionActionBarProps) => {
  const theme = useTheme();
  const insets = useSafeAreaInsets();

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
          <Button onPress={onCancel} mode="text">
            Cancel
          </Button>
          <Button
            onPress={onMove}
            mode="contained"
            disabled={selectedCount === 0}
          >
            Move
          </Button>
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
    paddingHorizontal: 16,
    paddingVertical: 12,
    minHeight: 56,
  },
  countText: {
    flex: 1,
  },
  actions: {
    flexDirection: "row",
    gap: 8,
  },
});
