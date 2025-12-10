import React from 'react';
import { View, StyleSheet, Image } from 'react-native';
import { Text, useTheme } from 'react-native-paper';
import { Story } from '../../types';

interface StoryHeaderProps {
    story: Story;
}

export const StoryHeader: React.FC<StoryHeaderProps> = ({ story }) => {
    const theme = useTheme();

    return (
        <View style={styles.container}>
            {story.coverUrl && <Image source={{ uri: story.coverUrl }} style={styles.coverImage} />}
            <Text variant="headlineMedium" style={styles.title}>{story.title}</Text>
            <Text variant="titleMedium" style={[styles.author, { color: theme.colors.secondary }]}>{story.author}</Text>
            
            <View style={styles.stats}>
                <Text variant="bodyMedium">Chapters: {story.totalChapters}</Text>
                <Text variant="bodyMedium">Downloaded: {story.downloadedChapters}</Text>
            </View>
        </View>
    );
};

const styles = StyleSheet.create({
    container: {
        width: '100%',
        alignItems: 'center',
    },
    coverImage: {
      width: 150,
      height: 225,
      borderRadius: 8,
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
});
