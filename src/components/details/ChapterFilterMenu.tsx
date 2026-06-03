import React from "react";
import { View, StyleSheet } from "react-native";
import { ChapterFilterMode } from "../../services/storage/StorageService";
import { useChapterFilterMenu } from "../../hooks/details/useChapterFilterMenu";
import { ChapterSearchbar } from "./ChapterSearchbar";
import { ChapterSelectionButton } from "./ChapterSelectionButton";
import { ChapterFilterDropdown } from "./ChapterFilterDropdown";

interface ChapterFilterMenuProps {
  filterMode: ChapterFilterMode;
  hasBookmark: boolean;
  onFilterSelect: (mode: ChapterFilterMode) => void;
  searchQuery: string;
  onSearchChange: (query: string) => void;
  selectionMode: boolean;
  onToggleSelectionMode: () => void;
  selectionDisabled?: boolean;
  stacked?: boolean;
}

export const ChapterFilterMenu: React.FC<ChapterFilterMenuProps> = ({
  filterMode,
  hasBookmark,
  onFilterSelect,
  searchQuery,
  onSearchChange,
  selectionMode,
  onToggleSelectionMode,
  selectionDisabled = false,
  stacked = false,
}) => {
  const { visible, menuKey, openMenu, handleDismiss, handleSelect } =
    useChapterFilterMenu({ onFilterSelect });

  return (
    <View style={styles.container}>
      <ChapterSearchbar
        searchQuery={searchQuery}
        onSearchChange={onSearchChange}
        stacked={stacked}
      />
      <View style={[styles.buttonGroup, stacked && styles.buttonGroupStacked]}>
        <ChapterSelectionButton
          selectionMode={selectionMode}
          onToggleSelectionMode={onToggleSelectionMode}
          selectionDisabled={selectionDisabled}
        />
        <ChapterFilterDropdown
          filterMode={filterMode}
          hasBookmark={hasBookmark}
          visible={visible}
          menuKey={menuKey}
          openMenu={openMenu}
          handleDismiss={handleDismiss}
          handleSelect={handleSelect}
        />
      </View>
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    flexDirection: "row",
    alignItems: "center",
    flexWrap: "wrap",
    gap: 8,
  },
  buttonGroup: {
    flexDirection: "row",
    alignItems: "center",
  },
  buttonGroupStacked: {
    alignSelf: "flex-end",
  },
});
