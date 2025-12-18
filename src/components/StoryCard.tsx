import React from 'react';
import { Image, StyleSheet, View } from 'react-native';
import { Card, Text, useTheme, ProgressBar, IconButton, Chip } from 'react-native-paper';

interface Props {
  title: string;
  author: string;
  coverUrl?: string;
  sourceName?: string;
  score?: string;
  progress?: number; // 0 to 1
  lastReadChapterName?: string;
  onPress: () => void;
}

export const StoryCard = ({ title, author, coverUrl, sourceName, score, progress, lastReadChapterName, onPress }: Props) => {
  const theme = useTheme();

  return (
    <Card style={[styles.card, { backgroundColor: theme.colors.surface }]} onPress={onPress} mode="elevated" elevation={1}>
      <Card.Content style={styles.content}>
        <View style={styles.imageContainer}>
          {coverUrl ? (
            <Image source={{ uri: coverUrl }} style={styles.coverImage as any} />
          ) : (
            <View style={[styles.coverImage as any, { backgroundColor: theme.colors.surfaceVariant, justifyContent: 'center', alignItems: 'center' }]}>
               <IconButton icon="book" size={32} iconColor={theme.colors.onSurfaceVariant} />
            </View>
          )}
        </View>
        <View style={styles.textContainer}>
            <Text variant="titleMedium" numberOfLines={2} style={[styles.title, { color: theme.colors.onSurface }]}>{title}</Text>
            <Text variant="bodySmall" style={[styles.author, { color: theme.colors.onSurfaceVariant }]}>{author}</Text>
            
            <View style={styles.metaContainer}>
              {sourceName && (
                  <Chip compact style={styles.sourceChip} textStyle={styles.chipText}>
                      {sourceName}
                  </Chip>
              )}
              {score && (
                  <View style={styles.scoreBadge}>
                      <IconButton icon="star" iconColor="#F59E0B" size={14} style={styles.scoreIcon} />
                      <Text variant="labelSmall" style={[styles.scoreText, { color: theme.colors.onSurface }]}>{score}</Text>
                  </View>
              )}
            </View>

            {lastReadChapterName && (
                <View style={styles.lastReadContainer}>
                  <IconButton icon="history" size={14} style={styles.historyIcon} iconColor={theme.colors.onSurfaceVariant} />
                  <Text variant="labelSmall" style={{ color: theme.colors.onSurfaceVariant, flex: 1 }} numberOfLines={1}>
                      {lastReadChapterName}
                  </Text>
                </View>
            )}
        </View>
      </Card.Content>
      {progress !== undefined && progress > 0 && (
        <ProgressBar 
          progress={progress} 
          style={[styles.progress, { backgroundColor: theme.colors.surfaceVariant }]} 
          color={theme.colors.primary}
        />
      )}
    </Card>
  );
};

const styles = StyleSheet.create({
  card: {
    borderRadius: 16,
    overflow: 'hidden',
    borderWidth: 1,
    borderColor: 'transparent',
  },
  content: {
    flexDirection: 'row',
    padding: 12,
  },
  imageContainer: {
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 4 },
    shadowOpacity: 0.1,
    shadowRadius: 8,
    elevation: 4,
  },
  coverImage: {
    width: 90,
    height: 130,
    borderRadius: 12,
  },
  textContainer: {
    flex: 1,
    marginLeft: 16,
    justifyContent: 'space-between',
    paddingVertical: 2,
  },
  title: {
    fontWeight: '700',
    lineHeight: 20,
    marginBottom: 2,
  },
  author: {
    fontWeight: '500',
    marginBottom: 8,
  },
  metaContainer: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    marginBottom: 8,
    flexWrap: 'wrap',
  },
  sourceChip: {
    height: 24,
    borderRadius: 6,
    backgroundColor: 'rgba(79, 70, 229, 0.1)',
  },
  chipText: {
    fontSize: 10,
    fontWeight: 'bold',
    color: '#4F46E5',
    marginVertical: 0,
    paddingVertical: 0,
  },
  scoreBadge: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: 'rgba(245, 158, 11, 0.1)',
    borderRadius: 6,
    paddingRight: 8,
    height: 24,
  },
  scoreIcon: {
    margin: 0,
    padding: 0,
    width: 24,
    height: 24,
  },
  scoreText: {
    fontWeight: 'bold',
    fontSize: 11,
  },
  lastReadContainer: {
    flexDirection: 'row',
    alignItems: 'center',
    marginTop: 'auto',
  },
  historyIcon: {
    margin: 0,
    marginLeft: -8,
  },
  progress: {
    height: 3,
  },
});
