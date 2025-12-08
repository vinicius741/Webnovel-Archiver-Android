import React, { useEffect, useState } from 'react';
import { View, StyleSheet, ScrollView } from 'react-native';
import { Text, Button, List, useTheme, ActivityIndicator } from 'react-native-paper';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { ScreenContainer } from '../../src/components/ScreenContainer';
import { storageService } from '../../src/services/StorageService';
import { Story } from '../../src/types';

export default function StoryDetailsScreen() {
  const { id } = useLocalSearchParams();
  const theme = useTheme();
  const router = useRouter();
  const [story, setStory] = useState<Story | null>(null);
  const [loading, setLoading] = useState(true);

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
      <ScrollView contentContainerStyle={styles.content}>
        <Text variant="headlineMedium" style={styles.title}>{story.title}</Text>
        <Text variant="titleMedium" style={{ color: theme.colors.secondary }}>{story.author}</Text>
        
        <View style={styles.stats}>
             <Text variant="bodyMedium">Chapters: {story.totalChapters}</Text>
             <Text variant="bodyMedium">Downloaded: {story.downloadedChapters}</Text>
        </View>

        <Button mode="contained" style={styles.actionBtn} onPress={() => {}}>
            {story.downloadedChapters === story.totalChapters ? 'Read' : 'Download All'}
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
    paddingTop: 60,
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
