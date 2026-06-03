import React from "react";
import { StyleSheet } from "react-native";
import { IconButton, useTheme } from "react-native-paper";

interface ChapterSelectionButtonProps {
  selectionMode: boolean;
  onToggleSelectionMode: () => void;
  selectionDisabled: boolean;
}

export const ChapterSelectionButton: React.FC<ChapterSelectionButtonProps> = ({
  selectionMode,
  onToggleSelectionMode,
  selectionDisabled,
}) => {
  const theme = useTheme();

  return (
    <IconButton
      icon={selectionMode ? "close" : "checkbox-marked-outline"}
      onPress={onToggleSelectionMode}
      size={20}
      disabled={selectionDisabled}
      style={[
        styles.filterButton,
        styles.selectionButton,
        selectionMode && { backgroundColor: theme.colors.primaryContainer },
      ]}
      iconColor={selectionMode ? theme.colors.primary : undefined}
      testID="chapter-selection-button"
    />
  );
};

const styles = StyleSheet.create({
  filterButton: {
    margin: 0,
    marginRight: 4,
  },
  selectionButton: {
    marginLeft: 8,
  },
});
