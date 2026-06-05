import React from "react";
import { StyleSheet, View, FlatList, RefreshControl } from "react-native";
import { Text, MD3Theme } from "react-native-paper";
import { Story } from "../../types";

interface TabFlatListProps {
  tabId: string;
  stories: Story[];
  numColumns: number;
  itemWidth: number;
  isLargeScreen: boolean;
  maxContentWidth: number;
  selectionMode: boolean;
  refreshing: boolean;
  onRefresh: () => void;
  renderStoryItem: (info: { item: Story }) => React.ReactElement | null;
  theme: MD3Theme;
}

export const TabFlatList = React.memo(function TabFlatList({
  stories,
  numColumns,
  itemWidth: _itemWidth,
  isLargeScreen,
  maxContentWidth,
  selectionMode,
  refreshing,
  onRefresh,
  renderStoryItem,
  theme,
}: TabFlatListProps) {
  return (
    <View style={styles.pageContainer}>
      <FlatList
        key={String(numColumns)}
        style={styles.flatList}
        data={stories}
        numColumns={numColumns}
        extraData={numColumns}
        columnWrapperStyle={numColumns > 1 ? styles.storyRow : undefined}
        keyExtractor={(item) => item.id}
        contentContainerStyle={[
          styles.listContent,
          isLargeScreen && styles.largeListContent,
          isLargeScreen && { maxWidth: maxContentWidth },
          selectionMode && { paddingBottom: 100 },
        ]}
        refreshControl={
          <RefreshControl
            refreshing={refreshing}
            onRefresh={onRefresh}
            colors={[theme.colors.primary]}
          />
        }
        renderItem={renderStoryItem}
        ListEmptyComponent={
          <View style={styles.emptyState}>
            <Text variant="bodyLarge" style={styles.placeholder}>
              No stories in this tab.
            </Text>
          </View>
        }
      />
    </View>
  );
});

const styles = StyleSheet.create({
  pageContainer: {
    flex: 1,
    width: "100%",
  },
  flatList: {
    width: "100%",
  },
  listContent: {
    padding: 16,
    paddingBottom: 80,
  },
  storyRow: {
    alignItems: "stretch",
    gap: 8,
  },
  largeListContent: {
    width: "100%",
    alignSelf: "center",
  },
  emptyState: {
    flex: 1,
    alignItems: "center",
    justifyContent: "center",
    marginTop: 100,
  },
  placeholder: {
    opacity: 0.6,
  },
});
