import React from "react";
import { StyleSheet } from "react-native";
import { Searchbar } from "react-native-paper";

interface ChapterSearchbarProps {
  searchQuery: string;
  onSearchChange: (query: string) => void;
  stacked?: boolean;
}

export const ChapterSearchbar: React.FC<ChapterSearchbarProps> = ({
  searchQuery,
  onSearchChange,
  stacked = false,
}) => {
  return (
    <Searchbar
      placeholder="Search chapters"
      onChangeText={onSearchChange}
      value={searchQuery}
      style={[styles.searchbar, stacked && styles.searchbarStacked]}
      inputStyle={styles.searchInput}
    />
  );
};

const styles = StyleSheet.create({
  searchbar: {
    flexGrow: 1,
    flexShrink: 1,
    flexBasis: 260,
    height: 48,
    alignItems: "center",
  },
  searchbarStacked: {
    flexBasis: "100%",
  },
  searchInput: {
    minHeight: 0,
    alignSelf: "center",
  },
});
