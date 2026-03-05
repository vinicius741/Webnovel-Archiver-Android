import React from "react";
import { View, StyleSheet } from "react-native";
import { List, useTheme, Checkbox } from "react-native-paper";
import { Chapter } from "../../types";
import { sanitizeTitle } from "../../utils/stringUtils";

interface ChapterListItemProps {
  item: Chapter;
  isLastRead?: boolean;
  onPress?: () => void;
  onLongPress?: () => void;
  /** When true, shows checkboxes instead of file icons. Tap toggles selection instead of navigation. */
  selectionMode?: boolean;
  /** Whether this chapter is currently selected. Only meaningful in selectionMode. */
  selected?: boolean;
  /** Callback when checkbox is toggled. Required when selectionMode is true. */
  onToggleSelection?: () => void;
}

export const ChapterListItem: React.FC<ChapterListItemProps> = ({
  item,
  isLastRead,
  onPress,
  onLongPress,
  selectionMode,
  selected,
  onToggleSelection,
}) => {
  const theme = useTheme();
  const sanitizedTitle = sanitizeTitle(item.title);

  // In selection mode, already-downloaded chapters cannot be selected
  const canSelect = selectionMode && !item.downloaded;

  const handlePress = () => {
    if (canSelect && onToggleSelection) {
      onToggleSelection();
    } else if (!selectionMode && onPress) {
      onPress();
    }
  };

  return (
    <List.Item
      title={sanitizedTitle}
      titleStyle={[
        { fontSize: 16 },
        isLastRead
          ? { color: theme.colors.primary, fontWeight: "bold" }
          : undefined,
        selectionMode && item.downloaded
          ? { color: theme.colors.outline }
          : undefined,
      ]}
      description={
        item.downloaded
          ? "Available Offline"
          : selectionMode
            ? "Tap to select"
            : undefined
      }
      descriptionStyle={{
        fontSize: 12,
        color: theme.colors.secondary,
      }}
      left={(props) =>
        selectionMode ? (
          <View
            style={styles.checkboxContainer}
            accessibilityLabel={`${selected ? "Deselect" : "Select"} ${sanitizedTitle}`}
            accessibilityRole="checkbox"
            accessibilityState={{ selected, disabled: item.downloaded }}
          >
            <Checkbox
              status={selected ? "checked" : "unchecked"}
              onPress={canSelect ? onToggleSelection : undefined}
              color={theme.colors.primary}
              disabled={item.downloaded}
            />
          </View>
        ) : (
          <List.Icon
            {...props}
            icon={
              isLastRead
                ? "bookmark"
                : item.downloaded
                  ? "file-check-outline"
                  : "file-outline"
            }
            color={
              isLastRead
                ? theme.colors.primary
                : item.downloaded
                  ? theme.colors.secondary
                  : theme.colors.outline
            }
          />
        )
      }
      right={(props) =>
        item.downloaded ? (
          <List.Icon
            {...props}
            icon="check-circle-outline"
            color={theme.colors.secondary}
            style={{ marginVertical: 0, alignSelf: "center" }}
          />
        ) : null
      }
      testID="list-item"
      onPress={handlePress}
      onLongPress={selectionMode ? undefined : onLongPress}
      style={[
        { borderRadius: 8, marginVertical: 2, marginHorizontal: 8 },
        isLastRead
          ? { backgroundColor: theme.colors.primaryContainer + "30" }
          : undefined,
        selected
          ? { backgroundColor: theme.colors.primaryContainer + "50" }
          : undefined,
        selectionMode && item.downloaded
          ? { opacity: 0.5 }
          : undefined,
      ]}
    />
  );
};

const styles = StyleSheet.create({
  checkboxContainer: {
    justifyContent: "center",
    marginLeft: 8,
  },
});
