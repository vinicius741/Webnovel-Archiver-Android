import React, { useEffect, useCallback, useState } from 'react';
import { StyleSheet, View, FlatList, RefreshControl } from 'react-native';
import { Text, FAB, useTheme, Button, IconButton } from 'react-native-paper';
import { useRouter, useFocusEffect, Stack } from 'expo-router';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { ScreenContainer } from '../src/components/ScreenContainer';
import { StoryCard } from '../src/components/StoryCard';
import { fetchPage } from '../src/services/network/fetcher';
import { parseMetadata } from '../src/services/parser/metadata';
import { parseChapterList } from '../src/services/parser/chapterList';
import { storageService } from '../src/services/StorageService';
import { Story } from '../src/types';
import { useAppAlert } from '../src/context/AlertContext';

export default function HomeScreen() {
  const router = useRouter();
  const theme = useTheme();
  const insets = useSafeAreaInsets();
  const { showAlert } = useAppAlert();
  const [stories, setStories] = useState<Story[]>([]);
  const [refreshing, setRefreshing] = useState(false);

  const loadLibrary = async () => {
    try {
      setRefreshing(true);
      const library = await storageService.getLibrary();
      // Sort by dateAdded desc (newest first)
      // Fallback to 0 if undefined (older items)
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
      <FlatList
        data={stories}
        keyExtractor={(item) => item.id}
        contentContainerStyle={styles.listContent}
        refreshControl={
            <RefreshControl refreshing={refreshing} onRefresh={onRefresh} colors={[theme.colors.primary]} />
        }
        renderItem={({ item }) => (
            <StoryCard 
            title={item.title} 
            author={item.author} 
            coverUrl={item.coverUrl}
            progress={item.totalChapters > 0 ? item.downloadedChapters / item.totalChapters : 0} 
            onPress={() => router.push(`/details/${item.id}`)} 
            />
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
});
