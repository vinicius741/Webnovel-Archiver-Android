import React from 'react';
import { StyleSheet, View, FlatList, RefreshControl, ScrollView } from 'react-native';
import { Text, FAB, useTheme, IconButton, Searchbar, Chip } from 'react-native-paper';
import { useRouter, Stack } from 'expo-router';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { ScreenContainer } from '../src/components/ScreenContainer';
import { StoryCard } from '../src/components/StoryCard';
import { SortButton } from '../src/components/SortButton';
import { useScreenLayout } from '../src/hooks/useScreenLayout';
import { useLibrary, SortOption } from '../src/hooks/useLibrary';
import { sourceRegistry } from '../src/services/source/SourceRegistry';


export default function HomeScreen() {
  const router = useRouter();
  const theme = useTheme();
  const insets = useSafeAreaInsets();
  const { numColumns, isLargeScreen, screenWidth } = useScreenLayout();
  
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
  } = useLibrary();

  const handleSortSelect = (option: SortOption) => {
    if (sortOption === option) {
        // If clicking same option, we could toggle, but adhering to the component's explicit toggle button is cleaner.
        // Or we can keep the "click again to toggle" behavior if we want.
        // The previous logic did: if same, toggle.
        setSortDirection(sortDirection === 'asc' ? 'desc' : 'asc');
    } else {
        setSortOption(option);
        // Set default direction based on option
        if (option === 'title') {
            setSortDirection('asc');
        } else {
            setSortDirection('desc');
        }
    }
  };

  const handleToggleDirection = () => {
      setSortDirection(sortDirection === 'asc' ? 'desc' : 'asc');
  };

  // Calculate strict item width to prevent last item from stretching in grid
  const GAP = 8;
  const containerPadding = 0; // ScreenContainer padding removed
  const listPadding = isLargeScreen ? 32 : 32; // 16 * 2
  const totalPadding = containerPadding + listPadding;
  // Account for safe area insets which ScreenContainer (SafeAreaView) enforces
  const safeAreaHorizontal = insets.left + insets.right;
  const availableWidth = screenWidth - safeAreaHorizontal - totalPadding - ((numColumns - 1) * GAP);
  const itemWidth = availableWidth / numColumns;


  return (
    <ScreenContainer edges={['bottom', 'left', 'right']} style={{ paddingTop: 16, paddingBottom: 0 }}>
      <Stack.Screen 
        options={{
          headerRight: () => (
            <IconButton 
              icon="cog" 
              iconColor={theme.colors.onSurface}
              onPress={() => router.push('/settings')} 
            />
          ),
        }} 
      />
      <View style={styles.searchContainer}>
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
            {allTags.map(tag => (
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
        key={numColumns} // Force re-render when columns change
        data={filteredStories}
        numColumns={numColumns}
        columnWrapperStyle={numColumns > 1 ? { gap: 8 } : undefined}
        keyExtractor={(item) => item.id}
        contentContainerStyle={[styles.listContent, isLargeScreen && { paddingHorizontal: 16 }]}
        refreshControl={
            <RefreshControl refreshing={refreshing} onRefresh={onRefresh} colors={[theme.colors.primary]} />
        }
        renderItem={({ item }) => (
            <View style={{ width: numColumns > 1 ? itemWidth : undefined, flex: numColumns === 1 ? 1 : undefined, height: '100%', marginBottom: 8 }}>
                <StoryCard 
                    title={item.title} 
                    author={item.author} 
                    coverUrl={item.coverUrl}
                    sourceName={sourceRegistry.getProvider(item.sourceUrl)?.name}
                    progress={item.totalChapters > 0 ? item.downloadedChapters / item.totalChapters : 0} 
                    lastReadChapterName={item.lastReadChapterId ? item.chapters.find(c => c.id === item.lastReadChapterId)?.title : undefined}
                    onPress={() => router.push(`/details/${item.id}`)} 
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
      
      <FAB
        icon="plus"
        style={[styles.fab, { backgroundColor: theme.colors.primary, bottom: insets.bottom + 16 }]}
        onPress={() => router.push('/add')}
        color={theme.colors.onPrimary}
      />
    </ScreenContainer>
  );
}

const styles = StyleSheet.create({
  header: {
    padding: 16,
    paddingTop: 60,
    backgroundColor: 'transparent',
  },
  listContent: {
    padding: 16,
    paddingBottom: 80,
  },
  emptyState: {
      flex: 1,
      alignItems: 'center',
      justifyContent: 'center',
      marginTop: 100,
  },
  placeholder: {
    opacity: 0.6,
  },
  fab: {
    position: 'absolute',
    margin: 16,
    right: 0,
    bottom: 0,
  },
  searchContainer: {
    paddingHorizontal: 16,
    paddingBottom: 8,
    backgroundColor: 'transparent',
    width: '100%',
    maxWidth: 600,
    alignSelf: 'center',
  },
  searchRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  searchBar: {
    flex: 1,
    elevation: 2,
    borderRadius: 8,
  },
  tagsScroll: {
    marginTop: 12,
    marginHorizontal: -16, // Pull to edges to override parent padding
  },
  tagsContainer: {
    gap: 8,
    paddingHorizontal: 16, // Align content with search bar
    paddingRight: 16, // Ensure last item has padding
  },
  tagChip: {
    height: 32,
  },
});