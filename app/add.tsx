import React, { useState } from 'react';
import { StyleSheet, View } from 'react-native';
import { TextInput, Button, Text, IconButton, useTheme } from 'react-native-paper';
import { useRouter } from 'expo-router';
import * as Clipboard from 'expo-clipboard';
import { ScreenContainer } from '../src/components/ScreenContainer';
import { fetchPage } from '../src/services/network/fetcher';
import { parseMetadata } from '../src/services/parser/metadata';
import { parseChapterList } from '../src/services/parser/chapterList';
import { storageService } from '../src/services/StorageService';
import { Story } from '../src/types';
import { useAppAlert } from '../src/context/AlertContext';

export default function AddStoryScreen() {
  const router = useRouter();
  const theme = useTheme();
  const { showAlert } = useAppAlert();
  const [url, setUrl] = useState('');
  const [loading, setLoading] = useState(false);

  const handlePaste = async () => {
    const text = await Clipboard.getStringAsync();
    if (text) {
      setUrl(text);
    }
  };

  const handleAdd = async () => {
    if (!url) return;
    setLoading(true);
    try {
        console.log('[AddStory] Fetching:', url);
        const html = await fetchPage(url);
        
        const metadata = parseMetadata(html);
        const chapters = parseChapterList(html, url);

        if (chapters.length === 0) {
            showAlert('Error', 'No chapters found. Please check the URL.');
            setLoading(false);
            return;
        }

        // Generate a simple ID logic (in real app, use uuid or hash)
        // For RoyalRoad: https://www.royalroad.com/fiction/12345/name -> 12345
        let storyId = 'custom_' + Date.now();
        const rrMatch = url.match(/fiction\/(\d+)/);
        if (rrMatch) {
            storyId = 'rr_' + rrMatch[1];
        }

        const story: Story = {
            id: storyId,
            title: metadata.title,
            author: metadata.author,
            coverUrl: metadata.coverUrl,
            sourceUrl: url,
            status: 'idle', // Ready to download
            totalChapters: chapters.length,
            downloadedChapters: 0,
            chapters: chapters.map(c => ({
                id: c.url, // Using URL as ID for chapters for now
                title: c.title,
                url: c.url,
            })),
            lastUpdated: Date.now()
        };

        await storageService.addStory(story);
        setLoading(false);
        showAlert('Success', `Added "${metadata.title}" to library.`, [
            { text: 'OK', onPress: () => router.back() }
        ]);

    } catch (e) {
        console.error(e);
        showAlert('Error', 'Failed to fetch the novel. ' + (e as Error).message);
        setLoading(false);
    }
  };

  return (
    <ScreenContainer>
      <View style={styles.form}>
        <Text variant="titleMedium" style={styles.label}>Webnovel URL</Text>
        <View style={styles.inputContainer}>
            <TextInput
            mode="outlined"
            placeholder="https://www.royalroad.com/fiction/..."
            value={url}
            onChangeText={setUrl}
            autoCapitalize="none"
            keyboardType="url"
            style={styles.input}
            />
            <IconButton
                icon="content-paste"
                mode="contained-tonal"
                onPress={handlePaste}
                style={styles.pasteButton}
            />
        </View>
        <Button 
          mode="contained" 
          onPress={handleAdd} 
          loading={loading}
          disabled={loading || !url}
          style={styles.button}
        >
          Fetch Story
        </Button>
      </View>
    </ScreenContainer>
  );
}

const styles = StyleSheet.create({
  form: {
    paddingTop: 16,
  },
  label: {
    marginBottom: 8,
  },
  inputContainer: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 16,
  },
  input: {
    flex: 1,
    marginRight: 8,
  },
  pasteButton: {
    margin: 0,
  },
  button: {
    marginTop: 8,
  },
});
