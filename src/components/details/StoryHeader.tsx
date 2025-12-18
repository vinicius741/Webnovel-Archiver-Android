import React, { useRef, useState } from 'react';
import { View, StyleSheet, Image, Linking, Pressable } from 'react-native';
import { Text, useTheme, Chip, IconButton } from 'react-native-paper';
import ImageView from "react-native-image-viewing";
import { Story } from '../../types';
import { sourceRegistry } from '../../services/source/SourceRegistry';

interface StoryHeaderProps {
    story: Story;
}

export const StoryHeader: React.FC<StoryHeaderProps> = ({ story }) => {
    const theme = useTheme();
    const sourceName = sourceRegistry.getProvider(story.sourceUrl)?.name;
    const lastTap = useRef<number | null>(null);
    const [viewerVisible, setViewerVisible] = useState(false);

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

    const images = story.coverUrl ? [{ uri: story.coverUrl }] : [];

    return (
        <View style={styles.container}>
            {story.coverUrl && (
                <>
                    <Pressable onPress={() => setViewerVisible(true)} style={styles.imageContainer}>
                        <Image source={{ uri: story.coverUrl }} style={styles.coverImage as any} />
                    </Pressable>
                    <ImageView
                        images={images}
                        imageIndex={0}
                        visible={viewerVisible}
                        onRequestClose={() => setViewerVisible(false)}
                    />
                </>
            )}
            <Pressable onPress={handleTitlePress}>
                <Text variant="headlineSmall" style={[styles.title, { color: theme.colors.onSurface }]}>{story.title}</Text>
            </Pressable>
            <Text variant="titleMedium" style={[styles.author, { color: theme.colors.primary }]}>{story.author}</Text>
            
            {sourceName && (
                <Chip mode="outlined" style={styles.sourceChip} textStyle={{ fontSize: 11, fontWeight: '600' }}>{sourceName}</Chip>
            )}

            <View style={styles.stats}>
                {story.score && (
                    <View style={[styles.statItem, { backgroundColor: 'rgba(245, 158, 11, 0.1)' }]}>
                        <IconButton icon="star" iconColor="#F59E0B" size={14} style={styles.statIcon} />
                        <Text variant="labelSmall" style={[styles.scoreText, { color: theme.colors.onSurface }]}>{story.score}</Text>
                    </View>
                )}
                <View style={[styles.statItem, { backgroundColor: theme.colors.surfaceVariant }]}>
                    <IconButton icon="book-open-variant" size={14} style={styles.statIcon} iconColor={theme.colors.onSurfaceVariant} />
                    <Text variant="labelSmall" style={{ color: theme.colors.onSurfaceVariant }}>{story.totalChapters} Chapters</Text>
                </View>
                <View style={[styles.statItem, { backgroundColor: theme.colors.surfaceVariant }]}>
                    <IconButton icon="download" size={14} style={styles.statIcon} iconColor={theme.colors.onSurfaceVariant} />
                    <Text variant="labelSmall" style={{ color: theme.colors.onSurfaceVariant }}>{story.downloadedChapters} Saved</Text>
                </View>
            </View>
        </View>
    );
};

const styles = StyleSheet.create({
    container: {
        width: '100%',
        alignItems: 'center',
        paddingVertical: 16,
    },
    imageContainer: {
        shadowColor: '#000',
        shadowOffset: { width: 0, height: 10 },
        shadowOpacity: 0.2,
        shadowRadius: 15,
        elevation: 10,
        marginBottom: 24,
    },
    coverImage: {
      width: 160,
      height: 240,
      borderRadius: 16,
    },
    title: {
        fontWeight: 'bold',
        marginBottom: 4,
        textAlign: 'center',
        paddingHorizontal: 20,
    },
    author: {
      textAlign: 'center',
      fontWeight: '600',
      marginBottom: 12,
    },
    sourceChip: {
        marginBottom: 16,
        height: 28,
        borderRadius: 8,
    },
    stats: {
        flexDirection: 'row',
        flexWrap: 'wrap',
        gap: 8,
        marginTop: 4,
        marginBottom: 8,
        justifyContent: 'center',
    },
    statItem: {
        flexDirection: 'row',
        alignItems: 'center',
        borderRadius: 8,
        paddingRight: 10,
        height: 28,
    },
    statIcon: {
        margin: 0,
        width: 28,
        height: 28,
    },
    scoreText: {
        fontWeight: 'bold',
    },
});
