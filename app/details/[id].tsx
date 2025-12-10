import React, { useEffect, useState, useRef } from 'react';
import { getInfoAsync, getContentUriAsync } from 'expo-file-system/legacy';
import { startActivityAsync } from 'expo-intent-launcher';
import { View, StyleSheet, ScrollView, Alert, FlatList } from 'react-native';
import { Text, Button, List, useTheme, ActivityIndicator, IconButton } from 'react-native-paper';
import { StoryHeader } from '../../src/components/details/StoryHeader';
import { StoryActions } from '../../src/components/details/StoryActions';
import { StoryDescription } from '../../src/components/details/StoryDescription';
import { StoryTags } from '../../src/components/details/StoryTags';
import { ChapterListItem } from '../../src/components/details/ChapterListItem';
import { useLocalSearchParams, useRouter, Stack } from 'expo-router';
import { ScreenContainer } from '../../src/components/ScreenContainer';
import { activateKeepAwakeAsync, deactivateKeepAwake } from 'expo-keep-awake';

import { storageService } from '../../src/services/StorageService';
import { epubGenerator } from '../../src/services/EpubGenerator';
import { downloadService } from '../../src/services/DownloadService';
import { Story, Chapter, DownloadStatus } from '../../src/types';
import { fetchPage } from '../../src/services/network/fetcher';
import { parseChapterList } from '../../src/services/parser/chapterList';
import { parseMetadata } from '../../src/services/parser/metadata';
import { useScreenLayout } from '../../src/hooks/useScreenLayout';

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
  const lastTap = useRef(0);
  const { isLargeScreen } = useScreenLayout();


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

  const confirmDelete = () => {
      if (!story) return;
      Alert.alert(
          'Delete Novel',
          `Are you sure you want to delete "${story.title}"? This action cannot be undone.`,
          [
              {
                  text: 'Cancel',
                  style: 'cancel',
              },
              {
                  text: 'Delete',
                  style: 'destructive',
                  onPress: async () => {
                      await storageService.deleteStory(story.id);
                      if (router.canDismiss()) {
                          router.dismiss();
                      } else {
                          router.replace('/');
                      }
                  },
              },
          ]
      );
  };

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

  const handleDownloadOrUpdate = async () => {
    if (!story) return;

    // If already downloaded, check for updates
    if (story.downloadedChapters === story.totalChapters) {
        try {
            setCheckingUpdates(true);
            const html = await fetchPage(story.sourceUrl);
            const newChapters = parseChapterList(html, story.sourceUrl);
            const metadata = parseMetadata(html);
            
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

            // Check if tags changed
            const tagsChanged = JSON.stringify(story.tags) !== JSON.stringify(metadata.tags);

            if (hasUpdates || updatedChapters.length > story.chapters.length || tagsChanged) {
                const updatedStory: Story = {
                    ...story,
                    chapters: updatedChapters,
                    totalChapters: updatedChapters.length,
                    status: hasUpdates ? DownloadStatus.Partial : story.status, // Only reset status if new chapters
                    lastUpdated: Date.now(),
                    tags: metadata.tags, // Update tags
                    // Update other metadata if desirable
                    title: metadata.title || story.title,
                    author: metadata.author || story.author,
                    coverUrl: metadata.coverUrl || story.coverUrl,
                    description: metadata.description || story.description,
                };
                
                await storageService.addStory(updatedStory);
                setStory(updatedStory);
                
                if (hasUpdates) {
                    showAlert('Update Found', `Found ${updatedChapters.length - story.chapters.length} new chapters!`);
                } else if (tagsChanged) {
                    showAlert('Metadata Updated', 'Tags and details updated.');
                }
            } else {
                showAlert('No Updates', 'No new chapters or changes found.');
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
            setDownloadStatus(`${current}/${total}: ${title}`);
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
  };

  const handleGenerateOrRead = async () => {
      if (!story) return;

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
  };

  const renderStoryInfo = () => (
    <View style={styles.fullWidth}>
        <StoryHeader story={story} />
        <StoryActions 
            story={story}
            downloading={downloading}
            checkingUpdates={checkingUpdates}
            downloadProgress={downloadProgress}
            downloadStatus={downloadStatus}
            onDownloadOrUpdate={handleDownloadOrUpdate}
            onGenerateOrRead={handleGenerateOrRead}
        />
        <StoryTags tags={story.tags} />
        <StoryDescription description={story.description} />
    </View>
  );

  return (
    <ScreenContainer>
      <Stack.Screen 
        options={{ 
            title: story ? story.title : 'Details',
            headerRight: () => (
                <IconButton 
                    icon="delete" 
                    iconColor={theme.colors.error}
                    onPress={confirmDelete}
                />
            )
        }} 
      />

      {isLargeScreen ? (
          <View style={styles.largeScreenContainer}>
              <View style={styles.leftColumn}>
                  <ScrollView contentContainerStyle={{ padding: 16 }}>
                      {renderStoryInfo()}
                  </ScrollView>
              </View>
              <View style={styles.rightColumn}>
                  <List.Section title="Chapters">
                       {/* Header for list included in section title, but FlatList needs data */}
                       <FlatList
                          data={story.chapters}
                          renderItem={({ item }) => <ChapterListItem item={item} />}
                          keyExtractor={(item: Chapter) => item.id}
                          // Remove inner scroll if we want the whole right column to scroll, 
                          // but requirement is to make chapters scrollable. FlatList does this by default.
                       />
                  </List.Section>
              </View>
          </View>
      ) : (
          <FlatList
              contentContainerStyle={styles.content}
              data={story.chapters}
              renderItem={({ item }) => <ChapterListItem item={item} />}
              keyExtractor={(item: Chapter) => item.id}
              ListHeaderComponent={renderStoryInfo()}
              ListHeaderComponentStyle={{ marginBottom: 16 }}
          />
      )}
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
    flexGrow: 1,
  },
  largeScreenContainer: {
    flexDirection: 'row',
    gap: 32,
  },
  normalContainer: {
    flexDirection: 'column',
  },
  leftColumn: {
      flex: 1,
      minWidth: 200,
      maxWidth: 500,
      alignItems: 'stretch',
  },
  rightColumn: {
      flex: 2,
  },
  fullWidth: {
      width: '100%',
  },
});
