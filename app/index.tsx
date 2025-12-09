import React, { useEffect, useCallback, useState } from 'react';
import { StyleSheet, View, Alert, FlatList, RefreshControl } from 'react-native';
import { Text, FAB, useTheme, Button } from 'react-native-paper';
import { useRouter, useFocusEffect } from 'expo-router';
import { ScreenContainer } from '../src/components/ScreenContainer';
import { StoryCard } from '../src/components/StoryCard';
import { fetchPage } from '../src/services/network/fetcher';
import { parseMetadata } from '../src/services/parser/metadata';
import { parseChapterList } from '../src/services/parser/chapterList';
import { parseChapterContent } from '../src/services/parser/content';
import { saveChapter, saveMetadata } from '../src/services/storage/fileSystem';
import { storageService } from '../src/services/StorageService';
import { Story } from '../src/types';

export default function HomeScreen() {
  const router = useRouter();
  const theme = useTheme();
  const [stories, setStories] = useState<Story[]>([]);
  const [refreshing, setRefreshing] = useState(false);

  const loadLibrary = async () => {
    try {
      setRefreshing(true);
      const library = await storageService.getLibrary();
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

  const addTestStory = async () => {
      const newStory: Story = {
          id: 'test_story_' + Date.now(),
          title: 'Test Story ' + new Date().toLocaleTimeString(),
          author: 'Test Author',
          coverUrl: 'https://via.placeholder.com/150',
          sourceUrl: 'https://example.com',
          status: 'idle',
          totalChapters: 10,
          downloadedChapters: 0,
          chapters: []
      };
      await storageService.addStory(newStory);
      loadLibrary();
  };

  const runTestScrape = async () => {
    try {
      const url = 'https://www.royalroad.com/fiction/21220/mother-of-learning'; // Use a small one if possible
      console.log('[Test] Starting scrape for:', url);
      
      const html = await fetchPage(url);
      const metadata = parseMetadata(html);
      
      const chapters = parseChapterList(html, url);
      
      if (chapters.length > 0) {
        // Save to Library
        const story: Story = {
            id: 'rr_21220', // simple ID generation
            title: metadata.title,
            author: metadata.author,
            coverUrl: metadata.coverUrl,
            sourceUrl: url,
            status: 'downloading',
            totalChapters: chapters.length,
            downloadedChapters: 0,
            chapters: chapters.map(c => ({
                id: c.url,
                title: c.title,
                url: c.url,
            })),
            lastUpdated: Date.now()
        };
        await storageService.addStory(story);
        loadLibrary();
        Alert.alert('Library Updated', `Added ${metadata.title}`);
      }
    } catch (e) {
      console.error(e);
      Alert.alert('Scrape Unknown Error', (e as Error).message);
    }
  };

  return (
    <ScreenContainer>
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
                <Button mode="outlined" onPress={addTestStory} style={{ marginTop: 20 }}>
                    Add Dummy Story
                </Button>
                 <Button mode="contained" onPress={runTestScrape} style={{ marginTop: 10 }}>
                    Test Real Scrape (RR)
                </Button>
            </View>
        }
      />
      
      <FAB
        icon="plus"
        style={[styles.fab, { backgroundColor: theme.colors.primary }]}
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
