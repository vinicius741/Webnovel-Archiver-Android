import React, { useRef } from 'react';
import { View, StyleSheet, Image, Linking, Pressable } from 'react-native';
import { Text, useTheme, Chip } from 'react-native-paper';
import { Story } from '../../types';
import { sourceRegistry } from '../../services/source/SourceRegistry';

interface StoryHeaderProps {
    story: Story;
}

export const StoryHeader: React.FC<StoryHeaderProps> = ({ story }) => {
    const theme = useTheme();
    const sourceName = sourceRegistry.getProvider(story.sourceUrl)?.name;
    const lastTap = useRef<number | null>(null);

    const handleTitlePress = () => {
        const now = Date.now();
        const DOUBLE_PRESS_DELAY = 300;
        if (lastTap.current && (now - lastTap.current < DOUBLE_PRESS_DELAY)) {
             Linking.openURL(story.sourceUrl).catch(err => console.error("Couldn't load page", err));
             lastTap.current = null;
        } else {
            lastTap.current = now;
        }
    };

    return (
        <View style={styles.container}>
            {story.coverUrl && <Image source={{ uri: story.coverUrl }} style={styles.coverImage} />}
            <Pressable onPress={handleTitlePress}>
                <Text variant="headlineMedium" style={styles.title}>{story.title}</Text>
            </Pressable>
            <Text variant="titleMedium" style={[styles.author, { color: theme.colors.secondary }]}>{story.author}</Text>
            
            {sourceName && (
                <Chip icon="web" style={styles.sourceChip} textStyle={{ fontSize: 12 }}>{sourceName}</Chip>
            )}

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
      marginBottom: 8,
    },
    sourceChip: {
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
