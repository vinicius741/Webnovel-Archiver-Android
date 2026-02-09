import React, { useState, useEffect, useMemo } from 'react';
import { View, StyleSheet, ScrollView, FlatList } from 'react-native';
import { Text, Button, List, ActivityIndicator } from 'react-native-paper';
import { useLocalSearchParams, useRouter, Stack } from 'expo-router';

import { StoryHeader } from '../../src/components/details/StoryHeader';
import { StoryActions } from '../../src/components/details/StoryActions';
import { StoryMenu } from '../../src/components/details/StoryMenu';
import { StoryDescription } from '../../src/components/details/StoryDescription';
import { StoryTags } from '../../src/components/details/StoryTags';
import { ChapterListItem } from '../../src/components/details/ChapterListItem';
import { ChapterFilterMenu } from '../../src/components/details/ChapterFilterMenu';
import { DownloadRangeDialog } from '../../src/components/details/DownloadRangeDialog';
import { EpubConfigDialog } from '../../src/components/details/EpubConfigDialog';
import { ScreenContainer } from '../../src/components/ScreenContainer';
import { useScreenLayout } from '../../src/hooks/useScreenLayout';
import { useStoryDetails } from '../../src/hooks/useStoryDetails';
import { storageService, ChapterFilterMode } from '../../src/services/StorageService';
import { Chapter, EpubConfig } from '../../src/types';

export default function StoryDetailsScreen() {
  const { id } = useLocalSearchParams();
  const router = useRouter();
  const { isLargeScreen } = useScreenLayout();
  
  const {
      story,
      loading,
      downloading,
      syncing,
      generating,
      epubProgress,
      syncStatus,
      downloadProgress,
      downloadStatus,
      deleteStory,
      markChapterAsRead,
      syncChapters,
      generateOrRead,
      downloadRange,
      applySentenceRemoval,
      updateStory,
  } = useStoryDetails(id);

  const [searchQuery, setSearchQuery] = useState('');
  const [showDownloadRange, setShowDownloadRange] = useState(false);
  const [showEpubConfig, setShowEpubConfig] = useState(false);
  const [filterMode, setFilterMode] = useState<ChapterFilterMode>('all');
  const [defaultMaxChapters, setDefaultMaxChapters] = useState(150);

  useEffect(() => {
    const loadSettings = async () => {
      const [filterSettings, appSettings] = await Promise.all([
        storageService.getChapterFilterSettings(),
        storageService.getSettings(),
      ]);
      setFilterMode(filterSettings.filterMode);
      setDefaultMaxChapters(appSettings.maxChaptersPerEpub || 150);
    };
    loadSettings();
  }, []);

  const filteredChapters = useMemo(() => {
    if (!story) return [];

    const bookmarkIndex = story.lastReadChapterId && filterMode === 'hideAboveBookmark'
        ? story.chapters.findIndex(ch => ch.id === story.lastReadChapterId)
        : -1;

    return story.chapters.filter((c, index) => {
        const matchesSearch = c.title.toLowerCase().includes(searchQuery.toLowerCase());
        if (!matchesSearch) return false;

        switch (filterMode) {
            case 'hideNonDownloaded':
                return c.downloaded === true;
            case 'hideAboveBookmark':
                return bookmarkIndex !== -1 ? index >= bookmarkIndex : true;
            default:
                return true;
        }
    });
  }, [story, searchQuery, filterMode]);

  const handleFilterSelect = async (mode: ChapterFilterMode) => {
      setFilterMode(mode);
      await storageService.saveChapterFilterSettings({ filterMode: mode });
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

  const chapterCount = story.chapters.length;
  const downloadedChapterCount = story.chapters.filter(chapter => chapter.downloaded === true).length;
  const hasValidBookmark = !!story.lastReadChapterId
    && story.chapters.some(chapter => chapter.id === story.lastReadChapterId);
  const initialEpubConfig: EpubConfig = {
    maxChaptersPerEpub: story.epubConfig?.maxChaptersPerEpub ?? defaultMaxChapters,
    rangeStart: story.epubConfig?.rangeStart ?? 1,
    rangeEnd: story.epubConfig?.rangeEnd ?? chapterCount,
    startAfterBookmark: story.epubConfig?.startAfterBookmark ?? false,
  };

  const handleSaveEpubConfig = async (config: EpubConfig) => {
    const updatedStory = {
      ...story,
      epubConfig: config,
    };
    await updateStory(updatedStory);
  };

  const renderStoryInfo = () => (
    <View style={styles.fullWidth}>
        <StoryHeader story={story} />
        <StoryActions
            story={story}
            downloading={downloading}
            syncing={syncing}
            generating={generating}
            epubProgress={epubProgress}
            syncStatus={syncStatus}
            downloadProgress={downloadProgress}
            downloadStatus={downloadStatus}
            onSync={syncChapters}
            onGenerateOrRead={generateOrRead}
            onDownloadAll={() => story.totalChapters > 0 && downloadRange(1, story.totalChapters)}
        />
        <DownloadRangeDialog 
            visible={showDownloadRange}
            onDismiss={() => setShowDownloadRange(false)}
            onDownload={downloadRange}
            totalChapters={story.totalChapters}
        />
        <EpubConfigDialog
            visible={showEpubConfig}
            onDismiss={() => setShowEpubConfig(false)}
            onSave={handleSaveEpubConfig}
            initialConfig={initialEpubConfig}
            totalChapters={chapterCount}
            downloadedChapterCount={downloadedChapterCount}
            hasBookmark={hasValidBookmark}
        />
        <StoryTags tags={story.tags} />
        <StoryDescription description={story.description} />
        
        <View style={styles.filterContainer}>
            <ChapterFilterMenu
                filterMode={filterMode}
                hasBookmark={!!story.lastReadChapterId}
                onFilterSelect={handleFilterSelect}
                searchQuery={searchQuery}
                onSearchChange={setSearchQuery}
            />
        </View>
    </View>
  );

  return (
    <ScreenContainer edges={['bottom', 'left', 'right']}>
      <Stack.Screen 
        options={{ 
            title: story ? story.title : 'Details',
            headerRight: () => (
                <StoryMenu 
                    onDownloadRange={() => setShowDownloadRange(true)}
                    onConfigureEpub={() => setShowEpubConfig(true)}
                    onApplySentenceRemoval={applySentenceRemoval}
                    onDelete={deleteStory}
                    disabled={loading}
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
                       <FlatList
                          data={filteredChapters}
                          renderItem={({ item }) => (
                            <ChapterListItem 
                                item={item} 
                                isLastRead={story.lastReadChapterId === item.id}
                                onPress={() => router.push(`/reader/${story.id}/${encodeURIComponent(item.id)}`)}
                                onLongPress={() => markChapterAsRead(item)}
                            />
                          )}
                          keyExtractor={(item: Chapter) => item.id}
                       />
                  </List.Section>
              </View>
          </View>
      ) : (
          <FlatList
              contentContainerStyle={styles.content}
              data={filteredChapters}
              renderItem={({ item }) => (
                <ChapterListItem 
                    item={item} 
                    isLastRead={story.lastReadChapterId === item.id}
                    onPress={() => router.push(`/reader/${story.id}/${encodeURIComponent(item.id)}`)}
                    onLongPress={() => markChapterAsRead(item)}
                />
              )}
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
  filterContainer: {
    margin: 16,
    marginBottom: 8,
  },
});
