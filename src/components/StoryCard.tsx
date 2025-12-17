import React from 'react';
import { Image, StyleSheet, View } from 'react-native';
import { Card, Text, useTheme, ProgressBar, IconButton } from 'react-native-paper';

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
    <Card style={styles.card} onPress={onPress}>
      <Card.Content style={styles.content}>
        {coverUrl && <Image source={{ uri: coverUrl }} style={styles.coverImage} />}
        <View style={styles.textContainer}>
            <Text variant="titleMedium" numberOfLines={2} style={{ marginBottom: 4 }}>{title}</Text>
            <Text variant="bodySmall" style={{ color: theme.colors.onSurfaceVariant, marginBottom: 4 }}>{author}</Text>
            {sourceName && (
                <Text variant="labelSmall" style={{ color: theme.colors.primary, marginBottom: 4 }}>
                    {sourceName}
                </Text>
            )}
            {score && (
                <View style={styles.scoreContainer}>
                    <IconButton icon="star" iconColor="#FFD700" size={12} style={styles.scoreIcon} />
                    <Text variant="labelSmall" style={styles.scoreText}>{score}</Text>
                </View>
            )}
            {lastReadChapterName && (
                <Text variant="labelSmall" style={{ color: theme.colors.onSurfaceVariant }} numberOfLines={1}>
                    Last read: {lastReadChapterName}
                </Text>
            )}
        </View>
      </Card.Content>
      {progress !== undefined && (
        <ProgressBar progress={progress} style={styles.progress} />
      )}
    </Card>
  );
};

const styles = StyleSheet.create({
  card: {
    flex: 1,
    marginBottom: 0, // Handled by parent container now for grid gaps
    borderRadius: 12,
    marginTop: 8,
  },
  content: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    padding: 16,
  },
  coverImage: {
    width: 80,
    height: 120,
    borderRadius: 8,
    marginRight: 16,
  },
  textContainer: {
    flex: 1,
    justifyContent: 'center',
  },
  progress: {
    height: 4,
    borderBottomLeftRadius: 12,
    borderBottomRightRadius: 12,
  },
  scoreContainer: {
      flexDirection: 'row',
      alignItems: 'center',
      marginBottom: 4,
      marginLeft: -8, // Adjust for IconButton margin
  },
  scoreIcon: {
      margin: 0,
  },
  scoreText: {
      fontWeight: 'bold',
      marginLeft: -4,
  }
});
