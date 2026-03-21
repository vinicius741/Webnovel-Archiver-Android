import React, { useState, useCallback } from "react";
import {
  StyleSheet,
  View,
  FlatList,
  RefreshControl,
  ScrollView,
} from "react-native";
import {
  Text,
  FAB,
  useTheme,
  IconButton,
  Searchbar,
  Chip,
} from "react-native-paper";
import { useRouter, Stack } from "expo-router";
import { useSafeAreaInsets } from "react-native-safe-area-context";
import { ScreenContainer } from "../src/components/ScreenContainer";
import { StoryCard } from "../src/components/StoryCard";
import { SortButton } from "../src/components/SortButton";
import { LibraryTabBar } from "../src/components/library/LibraryTabBar";
import { MoveToTabDialog } from "../src/components/library/MoveToTabDialog";
import { SelectionActionBar } from "../src/components/library/SelectionActionBar";
import { useScreenLayout } from "../src/hooks/useScreenLayout";
import { useLibrary, SortOption } from "../src/hooks/useLibrary";
import { useTabs } from "../src/hooks/useTabs";
import { useLibrarySelection } from "../src/hooks/useLibrarySelection";
import { sourceRegistry } from "../src/services/source/SourceRegistry";

export default function HomeScreen() {
  const router = useRouter();
  const theme = useTheme();
  const insets = useSafeAreaInsets();
  const { numColumns, isLargeScreen, screenWidth } = useScreenLayout();

  const {
    tabs,
    activeTabId,
    hasCustomTabs,
    showUnassignedTab,
    unassignedCount,
    selectTab,
  } = useTabs();

  const {
    stories: filteredStories,
    refreshing,
    onRefresh,
    searchQuery,
    setSearchQuery,
    selectedTags,
    toggleTag,
    allTags,
    sortOption,
    setSortOption,
    sortDirection,
    setSortDirection,
  } = useLibrary({
    activeTabId,
    hasCustomTabs,
  });

  const {
    selectionMode,
    selectedCount,
    enterSelectionMode,
    exitSelectionMode,
    toggleSelection,
    isSelected,
    moveSelectedToTab,
  } = useLibrarySelection();

  const [moveDialogVisible, setMoveDialogVisible] = useState(false);

  const handleSortSelect = (option: SortOption) => {
    if (sortOption === option) {
      setSortDirection(sortDirection === "asc" ? "desc" : "asc");
    } else {
      setSortOption(option);
      if (option === "title") {
        setSortDirection("asc");
      } else {
        setSortDirection("desc");
      }
    }
  };

  const handleToggleDirection = () => {
    setSortDirection(sortDirection === "asc" ? "desc" : "asc");
  };

  const handleLongPress = useCallback(
    (storyId: string) => {
      if (!selectionMode) {
        enterSelectionMode();
        toggleSelection(storyId);
      } else {
        toggleSelection(storyId);
      }
    },
    [selectionMode, enterSelectionMode, toggleSelection],
  );

  const handleOpenMoveDialog = useCallback(() => {
    setMoveDialogVisible(true);
  }, []);

  const handleCloseMoveDialog = useCallback(() => {
    setMoveDialogVisible(false);
  }, []);

  const handleMove = useCallback(
    async (tabId: string | null) => {
      await moveSelectedToTab(tabId);
      setMoveDialogVisible(false);
      // Refresh the library
      onRefresh();
    },
    [moveSelectedToTab, onRefresh],
  );

  // Calculate strict item width to prevent last item from stretching in grid
  const GAP = 8;
  const containerPadding = 0;
  const listPadding = isLargeScreen ? 32 : 32;
  const totalPadding = containerPadding + listPadding;
  const safeAreaHorizontal = insets.left + insets.right;
  const availableWidth =
    screenWidth - safeAreaHorizontal - totalPadding - (numColumns - 1) * GAP;
  const itemWidth = availableWidth / numColumns;

  return (
    <ScreenContainer
      edges={["bottom", "left", "right"]}
      style={{ paddingTop: 16, paddingBottom: 0 }}
    >
      <Stack.Screen
        options={{
          headerRight: () => (
            <IconButton
              icon="cog"
              iconColor={theme.colors.onSurface}
              onPress={() => router.push("/settings")}
            />
          ),
        }}
      />
      <View style={styles.searchContainer}>
        {hasCustomTabs && (
          <LibraryTabBar
            tabs={tabs}
            activeTabId={activeTabId}
            showUnassignedTab={showUnassignedTab}
            unassignedCount={unassignedCount}
            onSelectTab={selectTab}
          />
        )}
        <View style={styles.searchRow}>
          <Searchbar
            placeholder="Search stories"
            onChangeText={setSearchQuery}
            value={searchQuery}
            style={styles.searchBar}
            placeholderTextColor={theme.colors.onSurfaceVariant}
            iconColor={theme.colors.onSurfaceVariant}
            inputStyle={{ color: theme.colors.onSurface }}
          />
          <SortButton
            sortOption={sortOption}
            sortDirection={sortDirection}
            onSortSelect={handleSortSelect}
            onToggleDirection={handleToggleDirection}
          />
        </View>

        {allTags.length > 0 && (
          <ScrollView
            horizontal
            showsHorizontalScrollIndicator={false}
            contentContainerStyle={styles.tagsContainer}
            style={styles.tagsScroll}
          >
            {allTags.map((tag) => (
              <Chip
                key={tag}
                selected={selectedTags.includes(tag)}
                onPress={() => toggleTag(tag)}
                style={styles.tagChip}
                showSelectedOverlay
                compact
              >
                {tag}
              </Chip>
            ))}
          </ScrollView>
        )}
      </View>
      <FlatList
        key={numColumns}
        data={filteredStories}
        numColumns={numColumns}
        columnWrapperStyle={numColumns > 1 ? { gap: 8 } : undefined}
        keyExtractor={(item) => item.id}
        contentContainerStyle={[
          styles.listContent,
          isLargeScreen && { paddingHorizontal: 16 },
          selectionMode && { paddingBottom: 100 },
        ]}
        refreshControl={
          <RefreshControl
            refreshing={refreshing}
            onRefresh={onRefresh}
            colors={[theme.colors.primary]}
          />
        }
        renderItem={({ item }) => (
          <View
            style={{
              width: numColumns > 1 ? itemWidth : undefined,
              flex: numColumns === 1 ? 1 : undefined,
              height: "100%",
              marginBottom: 8,
            }}
          >
            <StoryCard
              title={item.title}
              author={item.author}
              coverUrl={item.coverUrl}
              sourceName={sourceRegistry.getProvider(item.sourceUrl)?.name}
              score={item.score}
              isArchived={item.isArchived}
              progress={
                item.totalChapters > 0
                  ? item.downloadedChapters / item.totalChapters
                  : 0
              }
              lastReadChapterName={
                item.lastReadChapterId
                  ? item.chapters.find((c) => c.id === item.lastReadChapterId)
                      ?.title
                  : undefined
              }
              onPress={() => router.push(`/details/${item.id}`)}
              onLongPress={hasCustomTabs ? () => handleLongPress(item.id) : undefined}
              selectionMode={selectionMode}
              selected={isSelected(item.id)}
            />
          </View>
        )}
        ListEmptyComponent={
          <View style={styles.emptyState}>
            <Text variant="bodyLarge" style={styles.placeholder}>
              No stories archived yet.
            </Text>
          </View>
        }
      />

      {!selectionMode && (
        <FAB
          icon="plus"
          style={[
            styles.fab,
            { backgroundColor: theme.colors.primary, bottom: insets.bottom + 16 },
          ]}
          onPress={() => router.push("/add")}
          color={theme.colors.onPrimary}
        />
      )}

      {selectionMode && (
        <SelectionActionBar
          selectedCount={selectedCount}
          onMove={handleOpenMoveDialog}
          onCancel={exitSelectionMode}
        />
      )}

      <MoveToTabDialog
        visible={moveDialogVisible}
        onDismiss={handleCloseMoveDialog}
        tabs={tabs}
        selectedCount={selectedCount}
        onMove={handleMove}
      />
    </ScreenContainer>
  );
}

const styles = StyleSheet.create({
  listContent: {
    padding: 16,
    paddingBottom: 80,
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
  fab: {
    position: "absolute",
    margin: 16,
    right: 0,
    bottom: 0,
  },
  searchContainer: {
    paddingHorizontal: 16,
    paddingBottom: 12,
    backgroundColor: "transparent",
    width: "100%",
    maxWidth: 600,
    alignSelf: "center",
  },
  searchRow: {
    flexDirection: "row",
    alignItems: "center",
    gap: 8,
  },
  searchBar: {
    flex: 1,
    elevation: 2,
    borderRadius: 8,
  },
  tagsScroll: {
    marginTop: 12,
    marginHorizontal: -16,
  },
  tagsContainer: {
    gap: 8,
    paddingHorizontal: 16,
    paddingRight: 16,
  },
  tagChip: {
    height: 32,
  },
});
