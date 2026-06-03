import React from "react";
import { View, StyleSheet } from "react-native";
import { Menu, IconButton } from "react-native-paper";
import { ChapterFilterMode } from "../../services/storage/StorageService";

interface ChapterFilterDropdownProps {
  filterMode: ChapterFilterMode;
  hasBookmark: boolean;
  visible: boolean;
  menuKey: number;
  openMenu: () => void;
  handleDismiss: () => void;
  handleSelect: (mode: ChapterFilterMode) => void;
}

export const ChapterFilterDropdown: React.FC<ChapterFilterDropdownProps> = ({
  filterMode,
  hasBookmark,
  visible,
  menuKey,
  openMenu,
  handleDismiss,
  handleSelect,
}) => {
  return (
    <Menu
      key={menuKey}
      visible={visible}
      onDismiss={handleDismiss}
      anchor={
        <View collapsable={false}>
          <IconButton
            icon="filter-variant"
            onPress={openMenu}
            size={20}
            style={styles.filterButton}
            testID="chapter-filter-button"
          />
        </View>
      }
    >
      <Menu.Item
        onPress={() => handleSelect("all")}
        title="Show all chapters"
        leadingIcon={filterMode === "all" ? "check" : undefined}
      />
      <Menu.Item
        onPress={() => handleSelect("hideNonDownloaded")}
        title="Hide non-downloaded"
        leadingIcon={filterMode === "hideNonDownloaded" ? "check" : undefined}
      />
      <Menu.Item
        onPress={() => handleSelect("hideAboveBookmark")}
        title="Hide chapters above bookmark"
        leadingIcon={filterMode === "hideAboveBookmark" ? "check" : undefined}
        disabled={!hasBookmark}
      />
    </Menu>
  );
};

const styles = StyleSheet.create({
  filterButton: {
    margin: 0,
    marginRight: 4,
  },
});
