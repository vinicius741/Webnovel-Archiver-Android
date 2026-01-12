import React, { useRef, useState } from 'react';
import { View, StyleSheet, Image, Linking, Pressable } from 'react-native';
import { Text, useTheme, Chip, IconButton } from 'react-native-paper';
import ImageView from "../ImageViewer";
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
        <View testID="story-header" style={styles.container}>
            {story.coverUrl && (
                <>
                    <Pressable onPress={() => setViewerVisible(true)}>
                        <Image testID="image" source={{ uri: story.coverUrl }} style={styles.coverImage} />
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
                <Text variant="headlineMedium" style={styles.title}>{story.title}</Text>
            </Pressable>
            <Text variant="titleMedium" style={[styles.author, { color: theme.colors.secondary }]}>{story.author}</Text>
            
            {sourceName && (
                <Chip icon="web" style={styles.sourceChip} textStyle={{ fontSize: 12 }}>{sourceName}</Chip>
            )}

            <View style={styles.stats}>
                {story.score && (
                    <View style={styles.statItem}>
                        <IconButton icon="star" iconColor="#FFD700" size={16} style={styles.statIcon} />
                        <Text variant="bodyMedium" style={styles.scoreText}>{story.score}</Text>
                    </View>
                )}
                <View style={styles.statItem}>
                    <IconButton icon="book-open-variant" size={16} style={styles.statIcon} />
                    <Text variant="bodyMedium">{story.totalChapters} Chs</Text>
                </View>
                <View style={styles.statItem}>
                    <IconButton icon="download" size={16} style={styles.statIcon} />
                    <Text variant="bodyMedium">{story.downloadedChapters} Saved</Text>
                </View>
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
        flexWrap: 'wrap',
        gap: 8,
        marginTop: 8,
        marginBottom: 16,
        justifyContent: 'center',
    },
    statItem: {
        flexDirection: 'row',
        alignItems: 'center',
        backgroundColor: 'rgba(0,0,0,0.05)',
        borderRadius: 20,
        paddingRight: 12,
        height: 32,
    },
    statIcon: {
        margin: 0,
        marginRight: -4,
    },
    scoreText: {
        fontWeight: 'bold',
    },
});
