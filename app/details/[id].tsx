import React, { useState } from 'react';
import { View, StyleSheet, ScrollView, FlatList } from 'react-native';
import { Text, Button, List, useTheme, ActivityIndicator, IconButton, Searchbar, Switch } from 'react-native-paper';
import { useLocalSearchParams, useRouter, Stack } from 'expo-router';

import { StoryHeader } from '../../src/components/details/StoryHeader';
import { StoryActions } from '../../src/components/details/StoryActions';
import { StoryMenu } from '../../src/components/details/StoryMenu';
import { StoryDescription } from '../../src/components/details/StoryDescription';
import { StoryTags } from '../../src/components/details/StoryTags';
import { ChapterListItem } from '../../src/components/details/ChapterListItem';
import { DownloadRangeDialog } from '../../src/components/details/DownloadRangeDialog';
import { ScreenContainer } from '../../src/components/ScreenContainer';
import { useScreenLayout } from '../../src/hooks/useScreenLayout';
import { useStoryDetails } from '../../src/hooks/useStoryDetails';
import { Chapter } from '../../src/types';

export default function StoryDetailsScreen() {
  const { id } = useLocalSearchParams();
  const theme = useTheme();
  const router = useRouter();
  const { isLargeScreen } = useScreenLayout();
  
  const {
      story,
      loading,
      downloading,
      checkingUpdates,
      updateStatus,
      downloadProgress,
      downloadStatus,
      deleteStory,
      markChapterAsRead,
      downloadOrUpdate,
      generateOrRead,
      downloadRange,
      applySentenceRemoval,
  } = useStoryDetails(id);

  const [searchQuery, setSearchQuery] = useState('');
  const [showDownloadRange, setShowDownloadRange] = useState(false);
  const [hideNonDownloaded, setHideNonDownloaded] = useState(false);

  const filteredChapters = story?.chapters.filter(c => {
      const matchesSearch = c.title.toLowerCase().includes(searchQuery.toLowerCase());
      const matchesFilter = !hideNonDownloaded || c.downloaded;
      return matchesSearch && matchesFilter;
  }) || [];

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

  const renderStoryInfo = () => (
    <View style={styles.fullWidth}>
        <StoryHeader story={story} />
        <StoryActions 
            story={story}
            downloading={downloading}
            checkingUpdates={checkingUpdates}
            updateStatus={updateStatus}
            downloadProgress={downloadProgress}
            downloadStatus={downloadStatus}
            onDownloadOrUpdate={downloadOrUpdate}
            onGenerateOrRead={generateOrRead}
            onPartialDownload={() => setShowDownloadRange(true)}
        />
        <DownloadRangeDialog 
            visible={showDownloadRange}
            onDismiss={() => setShowDownloadRange(false)}
            onDownload={downloadRange}
            totalChapters={story.totalChapters}
        />
        <StoryTags tags={story.tags} />
        <StoryDescription description={story.description} />
        
        <View style={styles.filterContainer}>
            <Searchbar
                placeholder="Search chapters"
                onChangeText={setSearchQuery}
                value={searchQuery}
                style={styles.searchbar}
            />
             <View style={styles.toggleContainer}>
                <Text variant="bodyMedium">Hide non-downloaded</Text>
                <Switch
                    value={hideNonDownloaded}
                    onValueChange={setHideNonDownloaded}
                />
            </View>
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
    gap: 8,
  },
  searchbar: {
  },
  toggleContainer: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 4,
  },
});