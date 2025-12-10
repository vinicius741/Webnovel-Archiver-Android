import React from 'react';
import { Image, StyleSheet, View } from 'react-native';
import { Card, Text, useTheme, ProgressBar } from 'react-native-paper';

interface Props {
  title: string;
  author: string;
  coverUrl?: string;
  progress?: number; // 0 to 1
  onPress: () => void;
}

export const StoryCard = ({ title, author, coverUrl, progress, onPress }: Props) => {
  const theme = useTheme();

  return (
    <Card style={styles.card} onPress={onPress}>
      <Card.Content style={styles.content}>
        {coverUrl && <Image source={{ uri: coverUrl }} style={styles.coverImage} />}
        <View style={styles.textContainer}>
            <Text variant="titleMedium" numberOfLines={2} style={{ marginBottom: 8 }}>{title}</Text>
            <Text variant="bodySmall" style={{ color: theme.colors.onSurfaceVariant, marginTop: 4, marginBottom: 8 }}>{author}</Text>
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
});
