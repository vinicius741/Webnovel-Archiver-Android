import React, { useState, useCallback, useMemo, useEffect, useRef } from "react";
import {
  StyleSheet,
  View,
  ScrollView,
  Animated,
  Pressable,
} from "react-native";
import {
  IconButton,
  Searchbar,
  Chip,
  MD3Theme,
} from "react-native-paper";
import { LibraryTabBar } from "./LibraryTabBar";
import { SortButton } from "./SortButton";
import { Tab } from "../../types/tab";
import { SortOption } from "../../hooks/library/useLibrary";

interface LibraryFiltersProps {
  /** Whether custom tabs exist (controls collapsible behavior) */
  hasCustomTabs: boolean;
  /** Tab bar data */
  tabs: Tab[];
  activeTabId: string | null;
  showUnassignedTab: boolean;
  unassignedCount: number;
  onSelectTab: (tabId: string | null) => void;
  /** Search */
  searchQuery: string;
  onSearchQueryChange: (query: string) => void;
  /** Tags */
  selectedTags: string[];
  onToggleTag: (tag: string) => void;
  allTags: string[];
  tagCounts: Map<string, number>;
  /** Sort */
  sortOption: SortOption;
  sortDirection: "asc" | "desc";
  onSortSelect: (option: SortOption) => void;
  onToggleDirection: () => void;
  /** Styling */
  theme: MD3Theme;
}

export function LibraryFilters({
  hasCustomTabs,
  tabs,
  activeTabId,
  showUnassignedTab,
  unassignedCount,
  onSelectTab,
  searchQuery,
  onSearchQueryChange,
  selectedTags,
  onToggleTag,
  allTags,
  tagCounts,
  sortOption,
  sortDirection,
  onSortSelect,
  onToggleDirection,
  theme,
}: LibraryFiltersProps) {
  const [isFiltersExpanded, setIsFiltersExpanded] = useState(false);
  const [contentHeight, setContentHeight] = useState(200);

  const animatedHeight = useMemo(() => new Animated.Value(0), []);
  const animatedOpacity = useMemo(() => new Animated.Value(0), []);
  const animatedRotation = useMemo(() => new Animated.Value(0), []);
  const animationRef = useRef<Animated.CompositeAnimation | null>(null);

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

  const filterContent = (
    <>
      <View style={styles.searchRow}>
        <Searchbar
          placeholder="Search stories"
          onChangeText={onSearchQueryChange}
          value={searchQuery}
          style={styles.searchBar}
          placeholderTextColor={theme.colors.onSurfaceVariant}
          iconColor={theme.colors.onSurfaceVariant}
          inputStyle={{ color: theme.colors.onSurface }}
        />
        <View style={styles.sortButtonWrap}>
          <SortButton
            sortOption={sortOption}
            sortDirection={sortDirection}
            onSortSelect={onSortSelect}
            onToggleDirection={onToggleDirection}
          />
        </View>
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
              onPress={() => onToggleTag(tag)}
              style={styles.tagChip}
              showSelectedOverlay
              compact
            >
              {tag} ({tagCounts.get(tag) ?? 0})
            </Chip>
          ))}
        </ScrollView>
      )}
    </>
  );

  const filterToggleButton = (
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
            transform: [
              {
                rotate: animatedRotation.interpolate({
                  inputRange: [0, 1],
                  outputRange: ["0deg", "180deg"],
                }),
              },
            ],
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
  );

  return (
    <View style={styles.searchContainer}>
      {hasCustomTabs && (
        <LibraryTabBar
          tabs={tabs}
          activeTabId={activeTabId}
          showUnassignedTab={showUnassignedTab}
          unassignedCount={unassignedCount}
          onSelectTab={onSelectTab}
          trailing={filterToggleButton}
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
              overflow: "hidden",
            },
          ]}
        >
          <Animated.View style={{ opacity: animatedOpacity }}>
            <View onLayout={handleContentLayout}>{filterContent}</View>
          </Animated.View>
        </Animated.View>
      ) : (
        <View onLayout={handleContentLayout}>{filterContent}</View>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  searchContainer: {
    paddingHorizontal: 16,
    paddingBottom: 4,
    backgroundColor: "transparent",
    width: "100%",
    maxWidth: 840,
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
    flexWrap: "wrap",
    alignItems: "center",
    gap: 8,
    paddingTop: 8,
  },
  searchBar: {
    flexGrow: 1,
    flexBasis: 280,
    elevation: 2,
    borderRadius: 8,
  },
  sortButtonWrap: {
    alignSelf: "flex-start",
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
