import React from 'react';
import { StyleSheet, View } from 'react-native';
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
    marginBottom: 8,
  },
  content: {
    flexDirection: 'row',
    justifyContent: 'space-between',
  },
  textContainer: {
    flex: 1,
  },
  progress: {
    height: 4,
    borderBottomLeftRadius: 12, // Match card radius
    borderBottomRightRadius: 12,
  },
});
