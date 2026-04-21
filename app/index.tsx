import React, { useState, useCallback, useMemo, useEffect, useRef } from "react";
import {
  StyleSheet,
  View,
  FlatList,
  RefreshControl,
  ScrollView,
  Animated,
  Pressable,
} from "react-native";
import PagerView from "react-native-pager-view";
import {
  Text,
  FAB,
  useTheme as usePaperTheme,
  IconButton,
  Searchbar,
  Chip,
  MD3Theme,
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
import { Story } from "../src/types";

interface TabFlatListProps {
  tabId: string;
  stories: Story[];
  numColumns: number;
  itemWidth: number;
  isLargeScreen: boolean;
  selectionMode: boolean;
  refreshing: boolean;
  onRefresh: () => void;
  renderStoryItem: (info: { item: Story }) => React.ReactElement | null;
  theme: MD3Theme;
}

const TabFlatList = React.memo(function TabFlatList({
  stories,
  numColumns,
  itemWidth: _itemWidth,
  isLargeScreen,
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

export default function HomeScreen() {
  const router = useRouter();
  const theme = usePaperTheme();
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
    storiesByTabId,
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
  const [isFiltersExpanded, setIsFiltersExpanded] = useState(false);
  const [contentHeight, setContentHeight] = useState(200);

  const animatedHeight = useMemo(() => new Animated.Value(0), []);
  const animatedOpacity = useMemo(() => new Animated.Value(0), []);
  const animatedRotation = useMemo(() => new Animated.Value(0), []);
  const animationRef = useRef<Animated.CompositeAnimation | null>(null);
  const pagerRef = useRef<PagerView>(null);

  const pageTabIds = useMemo(() => {
    if (!hasCustomTabs) return [];
    const ids = tabs.map((t) => t.id);
    if (showUnassignedTab) {
      ids.push("unassigned");
    }
    return ids;
  }, [hasCustomTabs, tabs, showUnassignedTab]);

  const activePageIndex = useMemo(() => {
    const idx = pageTabIds.indexOf(activeTabId ?? "");
    return idx >= 0 ? idx : 0;
  }, [pageTabIds, activeTabId]);

  const hasActiveFilters = useMemo(
    () => searchQuery.length > 0 || selectedTags.length > 0,
    [searchQuery, selectedTags],
  );

  const handleContentLayout = useCallback(
    (e: { nativeEvent: { layout: { height: number } } }) => {
      const h = e.nativeEvent.layout.height;
      if (h > 0) setContentHeight(h);
    },
    [],
  );

  const toggleFilters = useCallback(() => {
    if (animationRef.current) {
      animationRef.current.stop();
    }
    const newValue = !isFiltersExpanded;
    setIsFiltersExpanded(newValue);

    const anim = Animated.parallel([
      Animated.timing(animatedHeight, {
        toValue: newValue ? 1 : 0,
        duration: 250,
        useNativeDriver: false,
      }),
      Animated.timing(animatedOpacity, {
        toValue: newValue ? 1 : 0,
        duration: 250,
        useNativeDriver: true,
      }),
      Animated.timing(animatedRotation, {
        toValue: newValue ? 1 : 0,
        duration: 250,
        useNativeDriver: true,
      }),
    ]);
    animationRef.current = anim;
    anim.start(() => {
      animationRef.current = null;
    });
  }, [isFiltersExpanded, animatedHeight, animatedOpacity, animatedRotation]);

  useEffect(() => {
    return () => {
      if (animationRef.current) {
        animationRef.current.stop();
      }
    };
  }, []);

  useEffect(() => {
    if (hasCustomTabs && pagerRef.current && activeTabId) {
      pagerRef.current.setPage(activePageIndex);
    }
  }, [activeTabId, activePageIndex, hasCustomTabs]);

  const handlePageSelected = useCallback(
    (e: { nativeEvent: { position: number } }) => {
      const tabId = pageTabIds[e.nativeEvent.position];
      if (tabId && tabId !== activeTabId) {
        selectTab(tabId);
      }
    },
    [pageTabIds, activeTabId, selectTab],
  );

  const handleSelectTabFromBar = useCallback(
    (tabId: string | null) => {
      selectTab(tabId);
      if (tabId && pagerRef.current) {
        const idx = pageTabIds.indexOf(tabId);
        if (idx >= 0) {
          pagerRef.current.setPage(idx);
        }
      }
    },
    [selectTab, pageTabIds],
  );

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
      onRefresh();
    },
    [moveSelectedToTab, onRefresh],
  );

  const GAP = 8;
  const listPadding = isLargeScreen ? 32 : 32;
  const totalPadding = listPadding;
  const safeAreaHorizontal = insets.left + insets.right;
  const availableWidth =
    screenWidth - safeAreaHorizontal - totalPadding - (numColumns - 1) * GAP;
  const itemWidth = availableWidth / numColumns;

  const renderStoryItem = useCallback(
    ({ item }: { item: (typeof filteredStories)[number] }) => (
      <View
        style={{
          width: numColumns > 1 ? itemWidth : undefined,
          flex: numColumns === 1 ? 1 : undefined,
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

  const filtersContent = (
    <>
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
    </>
  );

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
            onSelectTab={handleSelectTabFromBar}
            trailing={
              <Pressable
                onPress={toggleFilters}
                style={styles.filterButton}
                accessibilityLabel="Toggle filters"
                accessibilityHint="Show or hide search and tag filters"
                accessibilityState={{ expanded: isFiltersExpanded }}
              >
                <View style={styles.filterButtonInner}>
                  <Animated.View
                    style={{
                      transform: [{
                        rotate: animatedRotation.interpolate({
                          inputRange: [0, 1],
                          outputRange: ['0deg', '180deg'],
                        }),
                      }],
                    }}
                  >
                    <IconButton
                      icon="chevron-down"
                      size={20}
                      iconColor={theme.colors.onSurfaceVariant}
                      style={styles.filterIcon}
                    />
                  </Animated.View>
                  {hasActiveFilters && (
                    <View
                      style={[
                        styles.filterIndicator,
                        { backgroundColor: theme.colors.primary },
                      ]}
                    />
                  )}
                </View>
              </Pressable>
            }
          />
        )}

        {hasCustomTabs ? (
          <Animated.View
            style={[
              styles.filtersContainer,
              {
                height: animatedHeight.interpolate({
                  inputRange: [0, 1],
                  outputRange: [0, contentHeight],
                }),
                overflow: 'hidden',
              },
            ]}
          >
            <Animated.View style={{ opacity: animatedOpacity }}>
              <View onLayout={handleContentLayout}>{filtersContent}</View>
            </Animated.View>
          </Animated.View>
        ) : (
          <View onLayout={handleContentLayout}>{filtersContent}</View>
        )}
      </View>

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
  pager: {
    flex: 1,
  },
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
    paddingBottom: 4,
    backgroundColor: "transparent",
    width: "100%",
    maxWidth: 600,
    alignSelf: "center",
  },
  filtersContainer: {
    width: "100%",
  },
  filterButton: {
    padding: 4,
  },
  filterButtonInner: {
    position: "relative",
    alignItems: "center",
    justifyContent: "center",
  },
  filterIcon: {
    margin: 0,
    padding: 0,
  },
  filterIndicator: {
    position: "absolute",
    top: 4,
    right: 4,
    width: 8,
    height: 8,
    borderRadius: 4,
  },
  searchRow: {
    flexDirection: "row",
    alignItems: "center",
    gap: 8,
    paddingTop: 8,
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
