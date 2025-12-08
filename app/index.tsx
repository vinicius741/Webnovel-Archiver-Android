import React from 'react';
import { StyleSheet, View, Alert } from 'react-native';
import { Text, FAB, useTheme, Button } from 'react-native-paper';
import { useRouter } from 'expo-router';
import { ScreenContainer } from '../src/components/ScreenContainer';
import { StoryCard } from '../src/components/StoryCard';
import { fetchPage } from '../src/services/network/fetcher';
import { parseMetadata } from '../src/services/parser/metadata';
import { parseChapterList } from '../src/services/parser/chapterList';
import { parseChapterContent } from '../src/services/parser/content';
import { saveChapter, saveMetadata } from '../src/services/storage/fileSystem';

export default function HomeScreen() {
  const router = useRouter();
  const theme = useTheme();

  const runTestScrape = async () => {
    try {
      const url = 'https://www.royalroad.com/fiction/21220/mother-of-learning';
      console.log('[Test] Starting scrape for:', url);
      
      const html = await fetchPage(url);
      const metadata = parseMetadata(html);
      console.log('[Test] Metadata:', metadata);
      
      const chapters = parseChapterList(html, url);
      console.log('[Test] Chapters found:', chapters.length);
      
      if (chapters.length > 0) {
        const firstChapter = chapters[0];
        console.log('[Test] Fetching First Chapter:', firstChapter.url);
        
        const chapterHtml = await fetchPage(firstChapter.url);
        const content = parseChapterContent(chapterHtml);
        
        const novelId = 'test_novel_mol';
        await saveMetadata(novelId, metadata);
        const path = await saveChapter(novelId, 1, firstChapter.title, content);
        
        Alert.alert('Scrape Success', `Saved chapter to:\n${path}`);
      } else {
        Alert.alert('Scrape Error', 'No chapters found');
      }
    } catch (e) {
      console.error(e);
      Alert.alert('Scrape Unknown Error', (e as Error).message);
    }
  };

  return (
    <ScreenContainer>
      <View style={styles.content}>
        <Text variant="headlineMedium" style={styles.title}>Library</Text>
        
        <Button mode="contained" onPress={runTestScrape} style={{ marginBottom: 20 }}>
            Test Scrape Engine
        </Button>

        {/* Placeholder List */}
        {true ? (
             <StoryCard 
                title="The Beginning After The End" 
                author="TurtleMe" 
                progress={0.7} 
                onPress={() => {}} 
             />
        ) : (
            <Text variant="bodyLarge" style={styles.placeholder}>
            No stories archived yet. Tap + to add one.
            </Text>
        )}
      </View>
      
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
  content: {
    flex: 1,
    padding: 16,
    // justifyContent: 'center', // Removed to separate Button and List
    alignItems: 'center', 
    paddingTop: 60,
  },
  title: {
    marginBottom: 20,
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
