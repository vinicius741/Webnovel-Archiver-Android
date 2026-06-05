import React, { useCallback } from "react";
import { StyleSheet, View, FlatList, RefreshControl } from "react-native";
import PagerView from "react-native-pager-view";
import { Text, FAB, useTheme as usePaperTheme, IconButton } from "react-native-paper";
import { useRouter, Stack } from "expo-router";
import { useSafeAreaInsets } from "react-native-safe-area-context";
import { ScreenContainer } from "../src/components/common/ScreenContainer";
import { StoryCard } from "../src/components/library/StoryCard";
import { TabFlatList } from "../src/components/library/TabFlatList";
import { LibraryFilters } from "../src/components/library/LibraryFilters";
import { MoveToTabDialog } from "../src/components/library/MoveToTabDialog";
import { SelectionActionBar } from "../src/components/library/SelectionActionBar";
import { useLibrary } from "../src/hooks/library/useLibrary";
import { useTabs } from "../src/hooks/library/useTabs";
import { useLibrarySelection } from "../src/hooks/library/useLibrarySelection";
import { useLibraryPager } from "../src/hooks/library/useLibraryPager";
import { useSortControls } from "../src/hooks/library/useSortControls";
import { useLibraryActions } from "../src/hooks/library/useLibraryActions";
import { useLibraryLayout } from "../src/hooks/library/useLibraryLayout";
import { sourceRegistry } from "../src/services/source/SourceRegistry";
import { ErrorBoundary } from "../src/components/common/ErrorBoundary";

export default function HomeScreen() {
  const router = useRouter();
  const theme = usePaperTheme();
  const insets = useSafeAreaInsets();

  const { numColumns, isLargeScreen, maxContentWidth, itemWidth } =
    useLibraryLayout();

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
    storiesByTabId,
    refreshing,
    onRefresh,
    searchQuery,
    setSearchQuery,
    selectedTags,
    toggleTag,
    allTags,
    tagCounts,
    sortOption,
    setSortOption,
    sortDirection,
    setSortDirection,
  } = useLibrary({ activeTabId, hasCustomTabs });

  const {
    selectionMode,
    selectedCount,
    enterSelectionMode,
    exitSelectionMode,
    toggleSelection,
    isSelected,
    moveSelectedToTab,
    deleteSelectedStories,
  } = useLibrarySelection();

  const { pagerRef, pageTabIds, activePageIndex, handlePageSelected, handleSelectTabFromBar } =
    useLibraryPager({
      tabs,
      activeTabId,
      hasCustomTabs,
      showUnassignedTab,
      selectTab,
    });

  const { handleSortSelect, handleToggleDirection } = useSortControls({
    sortOption,
    sortDirection,
    setSortOption,
    setSortDirection,
  });

  const {
    moveDialogVisible,
    handleLongPress,
    handleOpenMoveDialog,
    handleCloseMoveDialog,
    handleMove,
    handleDelete,
  } = useLibraryActions({
    selectionMode,
    selectedCount,
    enterSelectionMode,
    toggleSelection,
    moveSelectedToTab,
    deleteSelectedStories,
    onRefresh,
  });

  const renderStoryItem = useCallback(
    ({ item }: { item: (typeof filteredStories)[number] }) => (
      <View
        style={[
          styles.storyItem,
          numColumns > 1 && styles.storyGridItem,
          numColumns > 1 ? { width: itemWidth } : styles.storySingleItem,
        ]}
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
          compact={numColumns >= 3}
        />
      </View>
    ),
    [
      numColumns,
      itemWidth,
      hasCustomTabs,
      selectionMode,
      isSelected,
      handleLongPress,
      router,
    ],
  );

  return (
    <ErrorBoundary contextLabel="Library">
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

        <LibraryFilters
          hasCustomTabs={hasCustomTabs}
          tabs={tabs}
          activeTabId={activeTabId}
          showUnassignedTab={showUnassignedTab}
          unassignedCount={unassignedCount}
          onSelectTab={handleSelectTabFromBar}
          searchQuery={searchQuery}
          onSearchQueryChange={setSearchQuery}
          selectedTags={selectedTags}
          onToggleTag={toggleTag}
          allTags={allTags}
          tagCounts={tagCounts}
          sortOption={sortOption}
          sortDirection={sortDirection}
          onSortSelect={handleSortSelect}
          onToggleDirection={handleToggleDirection}
          theme={theme}
        />

        {hasCustomTabs ? (
          <PagerView
            ref={pagerRef}
            style={styles.pager}
            initialPage={activePageIndex}
            onPageSelected={handlePageSelected}
            overdrag={false}
          >
            {pageTabIds.map((tabId) => (
              <TabFlatList
                key={`${tabId}-${numColumns}`}
                tabId={tabId}
                stories={storiesByTabId.get(tabId) ?? []}
                numColumns={numColumns}
                itemWidth={itemWidth}
                isLargeScreen={isLargeScreen}
                maxContentWidth={maxContentWidth}
                selectionMode={selectionMode}
                refreshing={refreshing}
                onRefresh={onRefresh}
                renderStoryItem={renderStoryItem}
                theme={theme}
              />
            ))}
          </PagerView>
        ) : (
          <FlatList
            key={numColumns}
            data={filteredStories}
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
                  No stories archived yet.
                </Text>
              </View>
            }
          />
        )}

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
            onDelete={handleDelete}
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
    </ErrorBoundary>
  );
}

const styles = StyleSheet.create({
  pager: {
    flex: 1,
  },
  storyRow: {
    alignItems: "stretch",
    gap: 8,
  },
  storyItem: {
    marginBottom: 8,
  },
  storyGridItem: {
    alignSelf: "stretch",
  },
  storySingleItem: {
    flex: 1,
  },
  listContent: {
    padding: 16,
    paddingBottom: 80,
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
  fab: {
    position: "absolute",
    margin: 16,
    right: 0,
    bottom: 0,
  },
});
