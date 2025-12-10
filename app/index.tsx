import React, { useEffect, useCallback, useState } from 'react';
import { StyleSheet, View, FlatList, RefreshControl } from 'react-native';
import { Text, FAB, useTheme, Button, IconButton, Searchbar } from 'react-native-paper';
import { useRouter, useFocusEffect, Stack } from 'expo-router';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { ScreenContainer } from '../src/components/ScreenContainer';
import { StoryCard } from '../src/components/StoryCard';
import { storageService } from '../src/services/StorageService';
import { Story } from '../src/types';
import { useScreenLayout } from '../src/hooks/useScreenLayout';


export default function HomeScreen() {
  const router = useRouter();
  const theme = useTheme();
  const insets = useSafeAreaInsets();
  const [stories, setStories] = useState<Story[]>([]);
  const [searchQuery, setSearchQuery] = useState('');
  const [refreshing, setRefreshing] = useState(false);
  const { numColumns, isLargeScreen, screenWidth } = useScreenLayout();


  const loadLibrary = async () => {
    try {
      setRefreshing(true);
      const library = await storageService.getLibrary();
      library.sort((a, b) => (b.dateAdded || 0) - (a.dateAdded || 0));
      
      setStories(library);
    } catch (e) {
      console.error(e);
    } finally {
        setRefreshing(false);
    }
  };

  useFocusEffect(
    useCallback(() => {
      loadLibrary();
    }, [])
  );

  const onRefresh = useCallback(() => {
    loadLibrary();
  }, []);

  const filteredStories = stories.filter(story => 
    story.title.toLowerCase().includes(searchQuery.toLowerCase()) ||
    (story.author && story.author.toLowerCase().includes(searchQuery.toLowerCase()))
  );



  return (
    <ScreenContainer style={{ paddingTop: 0, paddingBottom: 0 }}>
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
        <Searchbar
          placeholder="Search stories"
          onChangeText={setSearchQuery}
          value={searchQuery}
          style={styles.searchBar}
          placeholderTextColor={theme.colors.onSurfaceVariant}
          iconColor={theme.colors.onSurfaceVariant}
          inputStyle={{ color: theme.colors.onSurface }}
        />
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
            <View style={{ flex: 1, height: '100%', marginBottom: 8 }}>
                <StoryCard 
                    title={item.title} 
                    author={item.author} 
                    coverUrl={item.coverUrl}
                    progress={item.totalChapters > 0 ? item.downloadedChapters / item.totalChapters : 0} 
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
    padding: 8,
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
  searchBar: {
    elevation: 2,
    borderRadius: 8,
  },
});
