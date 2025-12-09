import React, { useEffect, useState } from 'react';
import { View, StyleSheet, ScrollView } from 'react-native';
import { Text, Button, List, useTheme, ActivityIndicator, ProgressBar } from 'react-native-paper';
import { useLocalSearchParams, useRouter, Stack } from 'expo-router';
import { ScreenContainer } from '../../src/components/ScreenContainer';
import { activateKeepAwakeAsync, deactivateKeepAwake } from 'expo-keep-awake';

import { storageService } from '../../src/services/StorageService';
import { epubGenerator } from '../../src/services/EpubGenerator';
import { downloadService } from '../../src/services/DownloadService';
import { Story } from '../../src/types';

import { Alert } from 'react-native';

export default function StoryDetailsScreen() {
  const { id } = useLocalSearchParams();
  const theme = useTheme();
  const router = useRouter();
  const [story, setStory] = useState<Story | null>(null);
  const [loading, setLoading] = useState(true);
  const [downloading, setDownloading] = useState(false);
  const [downloadProgress, setDownloadProgress] = useState(0);
  const [downloadStatus, setDownloadStatus] = useState('');

  useEffect(() => {
    const loadStory = async () => {
      if (typeof id === 'string') {
        const data = await storageService.getStory(id);
        if (data) {
            setStory(data);
        }
      }
      setLoading(false);
    };
    loadStory();
  }, [id]);

  if (loading) {
    return (
        <ScreenContainer>
            <View style={styles.center}>
                <ActivityIndicator size="large" />
            </View>
        </ScreenContainer>
    );
  }

  if (!story) {
      return (
          <ScreenContainer>
            <View style={styles.center}>
                <Text variant="headlineSmall">Story not found</Text>
                <Button onPress={() => router.back()}>Go Back</Button>
            </View>
          </ScreenContainer>
      );
  }

  return (
    <ScreenContainer>
      <Stack.Screen options={{ title: story ? story.title : 'Details' }} />
      <ScrollView contentContainerStyle={styles.content}>
        <Text variant="headlineMedium" style={styles.title}>{story.title}</Text>
        <Text variant="titleMedium" style={{ color: theme.colors.secondary }}>{story.author}</Text>
        
        <View style={styles.stats}>
             <Text variant="bodyMedium">Chapters: {story.totalChapters}</Text>
             <Text variant="bodyMedium">Downloaded: {story.downloadedChapters}</Text>
        </View>

        <Button 
            mode="contained" 
            style={styles.actionBtn} 
            loading={downloading}
            disabled={downloading}
            onPress={async () => {
                if (story.downloadedChapters === story.totalChapters) return; // Already read
                
                try {
                    setDownloading(true);
                    await activateKeepAwakeAsync();
                    
                    // Use the DownloadService
                    const updatedStory = await downloadService.downloadAllChapters(story, (total: number, current: number, title: string) => {
                         const progress = total > 0 ? current / total : 0;
                         setDownloadProgress(progress);
                         setDownloadStatus(`Downloading ${current}/${total}: ${title}`);
                    });
                    
                    setStory(updatedStory); // Update UI with new state
                    Alert.alert('Download Complete', 'All chapters have been downloaded.');
                    
                } catch (error) {
                    console.error('Download error', error);
                    Alert.alert('Download Error', 'Failed to download chapters. Check logs.');
                } finally {
                    setDownloading(false);
                    await deactivateKeepAwake();
                }
            }}
        >
            {downloading ? 'Downloading...' : (story.downloadedChapters === story.totalChapters ? 'Read' : 'Download All')}
        </Button>

        {downloading && (
            <View style={{ marginBottom: 20 }}>
                <ProgressBar progress={downloadProgress} color={theme.colors.primary} style={{ height: 8, borderRadius: 4 }} />
                <Text variant="bodySmall" style={{ marginTop: 8, textAlign: 'center' }}>
                    {downloadStatus}
                </Text>
            </View>
        )}

        <Button 
            mode="outlined" 
            style={styles.actionBtn}
            onPress={async () => {
                try {
                    setLoading(true);
                    const uri = await epubGenerator.generateEpub(story, story.chapters);
                    Alert.alert('Success', `EPUB exported to: ${uri}`);
                    setLoading(false);
                } catch (error: any) {
                    Alert.alert('Error', error.message);
                    setLoading(false);
                }
            }}
        >
            Export EPUB
        </Button>

        <Button 
            mode="outlined" 
            textColor={theme.colors.error} 
            style={[styles.actionBtn, { borderColor: theme.colors.error }]}
            onPress={async () => {
                await storageService.deleteStory(story.id);
                if (router.canDismiss()) {
                    router.dismiss();
                } else {
                    router.replace('/');
                }
            }}
        >
            Delete Novel
        </Button>

        <List.Section title="Chapters">
            {story.chapters.map((chapter, index) => (
                <List.Item
                    key={index}
                    title={chapter.title}
                    left={props => <List.Icon {...props} icon="file-document-outline" />}
                    onPress={() => {}}
                />
            ))}
        </List.Section>
      </ScrollView>
    </ScreenContainer>
  );
}

const styles = StyleSheet.create({
  center: {
      flex: 1,
      justifyContent: 'center',
      alignItems: 'center',
  },
  content: {
    padding: 16,
  },
  title: {
      marginBottom: 8,
  },
  stats: {
      flexDirection: 'row',
      gap: 16,
      marginTop: 8,
      marginBottom: 16,
  },
  actionBtn: {
      marginBottom: 20,
  }
});
