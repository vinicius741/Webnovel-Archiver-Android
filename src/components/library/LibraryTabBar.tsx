import React from "react";
import { StyleSheet, View, ScrollView } from "react-native";
import { Chip, useTheme } from "react-native-paper";
import { Tab } from "../../types/tab";

interface LibraryTabBarProps {
  tabs: Tab[];
  activeTabId: string | null;
  showUnassignedTab: boolean;
  unassignedCount: number;
  onSelectTab: (tabId: string | null) => void;
}

export const LibraryTabBar = ({
  tabs,
  activeTabId,
  showUnassignedTab,
  unassignedCount,
  onSelectTab,
}: LibraryTabBarProps) => {
  const theme = useTheme();

  return (
    <View style={styles.container}>
      <ScrollView
        horizontal
        showsHorizontalScrollIndicator={false}
        contentContainerStyle={styles.scrollContent}
      >
        {tabs.map((tab) => (
          <Chip
            key={tab.id}
            selected={activeTabId === tab.id}
            onPress={() => onSelectTab(tab.id)}
            style={styles.chip}
            showSelectedOverlay
            compact
          >
            {tab.name}
          </Chip>
        ))}
        {showUnassignedTab && (
          <Chip
            selected={activeTabId === "unassigned"}
            onPress={() => onSelectTab("unassigned")}
            style={styles.chip}
            showSelectedOverlay
            compact
          >
            Unassigned ({unassignedCount})
          </Chip>
        )}
      </ScrollView>
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    marginTop: 8,
  },
  scrollContent: {
    gap: 8,
    paddingHorizontal: 16,
  },
  chip: {
    height: 32,
  },
});
