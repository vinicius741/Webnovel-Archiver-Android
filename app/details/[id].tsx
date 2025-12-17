import React, { useState } from 'react';
import { View, StyleSheet, ScrollView, FlatList } from 'react-native';
import { Text, Button, List, useTheme, ActivityIndicator, IconButton, Searchbar } from 'react-native-paper';
import { useLocalSearchParams, useRouter, Stack } from 'expo-router';

import { StoryHeader } from '../../src/components/details/StoryHeader';
import { StoryActions } from '../../src/components/details/StoryActions';
import { StoryDescription } from '../../src/components/details/StoryDescription';
import { StoryTags } from '../../src/components/details/StoryTags';
import { ChapterListItem } from '../../src/components/details/ChapterListItem';
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
  } = useStoryDetails(id);

  const [searchQuery, setSearchQuery] = useState('');

  const filteredChapters = story?.chapters.filter(c => 
      c.title.toLowerCase().includes(searchQuery.toLowerCase())
  ) || [];

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
        />
        <StoryTags tags={story.tags} />
        <StoryDescription description={story.description} />
        <Searchbar
            placeholder="Search chapters"
            onChangeText={setSearchQuery}
            value={searchQuery}
            style={{ margin: 16, marginBottom: 8 }}
        />
    </View>
  );

  return (
    <ScreenContainer edges={['bottom', 'left', 'right']}>
      <Stack.Screen 
        options={{ 
            title: story ? story.title : 'Details',
            headerRight: () => (
                <IconButton 
                    icon="delete" 
                    iconColor={theme.colors.error}
                    onPress={deleteStory}
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
});