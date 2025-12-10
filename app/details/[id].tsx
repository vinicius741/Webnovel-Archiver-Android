import React, { useEffect, useState } from 'react';
import { getInfoAsync, getContentUriAsync } from 'expo-file-system/legacy';
import { startActivityAsync } from 'expo-intent-launcher';
import { View, StyleSheet, ScrollView, Image } from 'react-native';
import { Text, Button, List, useTheme, ActivityIndicator, ProgressBar } from 'react-native-paper';
import { useLocalSearchParams, useRouter, Stack } from 'expo-router';
import { ScreenContainer } from '../../src/components/ScreenContainer';
import { activateKeepAwakeAsync, deactivateKeepAwake } from 'expo-keep-awake';

import { storageService } from '../../src/services/StorageService';
import { epubGenerator } from '../../src/services/EpubGenerator';
import { downloadService } from '../../src/services/DownloadService';
import { Story, Chapter, DownloadStatus } from '../../src/types';
import { fetchPage } from '../../src/services/network/fetcher';
import { parseChapterList } from '../../src/services/parser/chapterList';

import { useAppAlert } from '../../src/context/AlertContext';

export default function StoryDetailsScreen() {
  const { id } = useLocalSearchParams();
  const { showAlert } = useAppAlert();
  const theme = useTheme();
  const router = useRouter();
  const [story, setStory] = useState<Story | null>(null);
  const [loading, setLoading] = useState(true);
  const [downloading, setDownloading] = useState(false);
  const [checkingUpdates, setCheckingUpdates] = useState(false);
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
        {story.coverUrl && <Image source={{ uri: story.coverUrl }} style={styles.coverImage} />}
        <Text variant="headlineMedium" style={styles.title}>{story.title}</Text>
        <Text variant="titleMedium" style={[styles.author, { color: theme.colors.secondary }]}>{story.author}</Text>
        
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
                // If already downloaded, check for updates
                if (story.downloadedChapters === story.totalChapters) {
                    try {
                        setCheckingUpdates(true);
                        const html = await fetchPage(story.sourceUrl);
                        const newChapters = parseChapterList(html, story.sourceUrl);
                        
                        // Merge logic
                        let hasUpdates = false;
                        const updatedChapters: Chapter[] = newChapters.map(newChap => {
                            const existing = story.chapters.find(c => c.url === newChap.url);
                            if (existing) {
                                return existing; // Keep existing state (downloaded, filePath)
                            }
                            hasUpdates = true;
                            return {
                                id: newChap.url,
                                title: newChap.title,
                                url: newChap.url,
                                downloaded: false,
                            };
                        });

                        if (hasUpdates || updatedChapters.length > story.chapters.length) {
                             const updatedStory: Story = {
                                 ...story,
                                 chapters: updatedChapters,
                                 totalChapters: updatedChapters.length,
                                 status: DownloadStatus.Partial, // Reset to partial so user can download new stuff
                                 lastUpdated: Date.now(),
                             };
                             
                             await storageService.addStory(updatedStory);
                             setStory(updatedStory);
                             showAlert('Update Found', `Found ${updatedChapters.length - story.chapters.length} new chapters!`);
                        } else {
                             showAlert('No Updates', 'No new chapters found.');
                        }

                    } catch (error: any) {
                        showAlert('Update Error', error.message);
                    } finally {
                        setCheckingUpdates(false);
                    }
                    return; 
                }
                
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
                    showAlert('Download Complete', 'All chapters have been downloaded.');
                    
                } catch (error) {
                    console.error('Download error', error);
                    showAlert('Download Error', 'Failed to download chapters. Check logs.');
                } finally {
                    setDownloading(false);
                    await deactivateKeepAwake();
                }
            }}
        >
            {downloading ? 'Downloading...' : (story.downloadedChapters === story.totalChapters ? (checkingUpdates ? 'Checking...' : 'Update') : 'Download All')}
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
                if (story.epubPath) {
                   try {
                       // Optimistic check: if it's a content URI, we assume it exists if we have permission (which SAF should have persisted).
                       // For file URIs, we can check.
                       const fileInfo = await getInfoAsync(story.epubPath);
                       if (!fileInfo.exists) {
                           // Invalid path, clear it
                            const updated = { ...story, epubPath: undefined };
                            await storageService.addStory(updated);
                            setStory(updated);
                            showAlert('Error', 'EPUB file not found. Please regenerate.');
                            return;
                       }

                       let contentUri = story.epubPath;
                       if (!contentUri.startsWith('content://')) {
                            contentUri = await getContentUriAsync(story.epubPath);
                       }
                       
                       await startActivityAsync('android.intent.action.VIEW', {
                           data: contentUri,
                           flags: 1, // FLAG_GRANT_READ_URI_PERMISSION
                           type: 'application/epub+zip',
                       });

                   } catch (e: any) {
                       showAlert('Read Error', 'Could not open EPUB: ' + e.message);
                   }
                   return;
                }

                try {
                    setLoading(true);
                    const uri = await epubGenerator.generateEpub(story, story.chapters);
                    
                    const updatedStory = { ...story, epubPath: uri };
                    await storageService.addStory(updatedStory);
                    setStory(updatedStory);

                    showAlert('Success', `EPUB exported to: ${uri}`);
                    setLoading(false);
                } catch (error: any) {
                    showAlert('Error', error.message);
                    setLoading(false);
                }
            }}
        >
            {story.epubPath ? 'Read EPUB' : 'Generate EPUB'}
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
  coverImage: {
    width: 150,
    height: 225,
    borderRadius: 8,
    alignSelf: 'center',
    marginBottom: 20,
  },
  title: {
      marginBottom: 8,
      textAlign: 'center',
  },
  author: {
    textAlign: 'center',
    marginBottom: 16,
  },
  stats: {
      flexDirection: 'row',
      gap: 16,
      marginTop: 8,
      marginBottom: 16,
      justifyContent: 'center',
  },
  actionBtn: {
      marginBottom: 20,
  }
});
