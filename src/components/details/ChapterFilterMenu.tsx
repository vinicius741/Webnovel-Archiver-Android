import React, { useState, useCallback, useRef } from "react";
import { View, StyleSheet } from "react-native";
import { Menu, IconButton, useTheme, Searchbar } from "react-native-paper";
import { ChapterFilterMode } from "../../services/StorageService";

interface ChapterFilterMenuProps {
  filterMode: ChapterFilterMode;
  hasBookmark: boolean;
  onFilterSelect: (mode: ChapterFilterMode) => void;
  searchQuery: string;
  onSearchChange: (query: string) => void;
  selectionMode: boolean;
  onToggleSelectionMode: () => void;
}

export const ChapterFilterMenu: React.FC<ChapterFilterMenuProps> = ({
  filterMode,
  hasBookmark,
  onFilterSelect,
  searchQuery,
  onSearchChange,
  selectionMode,
  onToggleSelectionMode,
}) => {
  const theme = useTheme();
  const [visible, setVisible] = useState(false);
  const [menuKey, setMenuKey] = useState(0);
  const isDismissedRef = useRef(false);

  const openMenu = useCallback(() => {
    // Prevent reopening immediately after dismiss (touch event conflict)
    if (isDismissedRef.current) {
      isDismissedRef.current = false;
      return;
    }
    setVisible(true);
  }, []);

  const closeMenu = useCallback(() => {
    setVisible(false);
    // Force remount of Menu component to reset internal state
    setMenuKey((k) => k + 1);
  }, []);

  const handleDismiss = useCallback(() => {
    isDismissedRef.current = true;
    closeMenu();
    // Reset the flag after a short delay
    setTimeout(() => {
      isDismissedRef.current = false;
    }, 150);
  }, [closeMenu]);

  const handleSelect = useCallback(
    (mode: ChapterFilterMode) => {
      closeMenu();
      // Small delay to let menu close animation complete before callback
      setTimeout(() => {
        onFilterSelect(mode);
      }, 100);
    },
    [onFilterSelect, closeMenu],
  );

  return (
    <View style={styles.container}>
      <Searchbar
        placeholder="Search chapters"
        onChangeText={onSearchChange}
        value={searchQuery}
        style={styles.searchbar}
        inputStyle={styles.searchInput}
      />
      <IconButton
        icon={selectionMode ? "close" : "checkbox-marked-outline"}
        onPress={onToggleSelectionMode}
        size={20}
        style={[
          styles.filterButton,
          styles.selectionButton,
          selectionMode && { backgroundColor: theme.colors.primaryContainer },
        ]}
        iconColor={selectionMode ? theme.colors.primary : undefined}
        testID="chapter-selection-button"
      />
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
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    flexDirection: "row",
    alignItems: "center",
  },
  searchbar: {
    flex: 1,
    height: 48,
    alignItems: "center",
  },
  searchInput: {
    minHeight: 0,
    alignSelf: "center",
  },
  filterButton: {
    margin: 0,
    marginRight: 4,
  },
  selectionButton: {
    marginLeft: 8,
  },
});
