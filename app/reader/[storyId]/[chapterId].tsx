import React, { useEffect, useState, useMemo } from 'react';
import { View, StyleSheet, ActivityIndicator } from 'react-native';
import { useLocalSearchParams, Stack, useRouter } from 'expo-router';
import { WebView } from 'react-native-webview';
import { IconButton, useTheme } from 'react-native-paper';
import { storageService } from '../../../src/services/StorageService';
import { readChapterFile } from '../../../src/services/storage/fileSystem';
import { Story, Chapter } from '../../../src/types';
import { ScreenContainer } from '../../../src/components/ScreenContainer';
import { useAppAlert } from '../../../src/context/AlertContext';
import { sanitizeTitle } from '../../../src/utils/stringUtils';
import { TTSSettingsModal } from '../../../src/components/TTSSettingsModal';
import { TTSController } from '../../../src/components/TTSController';
import { useTTS } from '../../../src/hooks/useTTS';
import { ReaderNavigation } from '../../../src/components/ReaderNavigation';

export default function ReaderScreen() {
    const { storyId, chapterId } = useLocalSearchParams<{ storyId: string; chapterId: string }>();
    const theme = useTheme();
    const router = useRouter();
    const { showAlert } = useAppAlert();
    
    // Data state
    const [story, setStory] = useState<Story | null>(null);
    const [chapter, setChapter] = useState<Chapter | null>(null);
    const [content, setContent] = useState<string>('');
    const [loading, setLoading] = useState(true);

    // TTS Hook
    const { 
        isSpeaking, 
        isPaused, 
        chunks, 
        currentChunkIndex, 
        ttsSettings, 
        isSettingsVisible, 
        isControllerVisible, 
        setIsSettingsVisible, 
        toggleSpeech, 
        stopSpeech, 
        handlePlayPause, 
        handleNextChunk, 
        handlePreviousChunk, 
        handleSettingsChange 
    } = useTTS();

    useEffect(() => {
        loadData();
    }, [storyId, chapterId]);

    // Stop speech when changing chapters
    useEffect(() => {
        stopSpeech();
    }, [chapterId]);

    const loadData = async () => {
        setLoading(true);
        try {
            const s = await storageService.getStory(storyId);
            if (s) {
                setStory(s);
                const c = s.chapters.find(chap => chap.id === decodeURIComponent(chapterId));
                if (c) {
                    setChapter(c);
                    if (c.filePath) {
                        const html = await readChapterFile(c.filePath);
                        setContent(html);
                    } else {
                        setContent('Chapter not downloaded yet.');
                    }
                }
            }
        } catch (e) {
            console.error('Failed to load chapter content', e);
        } finally {
            setLoading(false);
        }
    };

    const markAsRead = async () => {
        if (!story || !chapter) return;
        
        await storageService.updateLastRead(story.id, chapter.id);
        const updatedStory = { ...story, lastReadChapterId: chapter.id };
        setStory(updatedStory);
        showAlert('Marked as Read', `Marked "${chapter.title}" as your last read location.`);
    };

    const currentIndex = useMemo(() => {
        if (!story || !chapter) return -1;
        return story.chapters.findIndex(c => c.id === chapter.id);
    }, [story, chapter]);

    const hasNext = currentIndex < (story?.chapters.length || 0) - 1;
    const hasPrevious = currentIndex > 0;

    const navigateToChapter = (index: number) => {
        if (!story) return;
        const target = story.chapters[index];
        router.setParams({ chapterId: encodeURIComponent(target.id) });
    };

    const isLastRead = story?.lastReadChapterId === chapter?.id;

    const htmlContent = useMemo(() => {
        if (!content) return '';
        
        // Basic CSS for better reading experience
        const css = `
            body {
                background-color: ${theme.colors.surface};
                color: ${theme.colors.onSurface};
                font-family: sans-serif;
                padding: 16px;
                line-height: 1.6;
                font-size: 18px;
            }
            img {
                max-width: 100%;
                height: auto;
            }
        `;

        return `
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0">
                <style>${css}</style>
            </head>
            <body>
                ${content}
            </body>
            </html>
        `;
    }, [content, theme]);

    if (loading && !content) {
        return (
            <ScreenContainer>
                <View style={styles.center}>
                    <ActivityIndicator size="large" />
                </View>
            </ScreenContainer>
        );
    }

    return (
        <ScreenContainer edges={['bottom', 'left', 'right']}>
            <Stack.Screen 
                options={{ 
                    title: chapter ? sanitizeTitle(chapter.title) : 'Reader',
                    headerRight: () => (
                        <View style={{ flexDirection: 'row' }}>
                            <IconButton 
                                icon={isSpeaking ? "stop" : "volume-high"} 
                                iconColor={isSpeaking ? theme.colors.error : undefined}
                                onPress={() => toggleSpeech(content)}
                            />

                            <IconButton 
                                icon="cog-outline" 
                                onPress={() => setIsSettingsVisible(true)}
                            />

                            <IconButton 
                                icon={isLastRead ? "bookmark" : "bookmark-outline"} 
                                iconColor={isLastRead ? theme.colors.primary : undefined}
                                onPress={markAsRead}
                            />
                        </View>
                    )
                }} 
            />

            <TTSSettingsModal 
                visible={isSettingsVisible}
                onDismiss={() => setIsSettingsVisible(false)}
                settings={ttsSettings}
                onSettingsChange={handleSettingsChange}
            />

            <TTSController 
                visible={isControllerVisible}
                isSpeaking={isSpeaking}
                isPaused={isPaused}
                currentChunk={currentChunkIndex}
                totalChunks={chunks.length}
                onPlayPause={handlePlayPause}
                onStop={stopSpeech}
                onNext={handleNextChunk}
                onPrevious={handlePreviousChunk}
            />

            
            <View style={styles.container}>
                <WebView
                    originWhitelist={['*']}
                    source={{ html: htmlContent }}
                    style={{ backgroundColor: theme.colors.surface }}
                />
                
                <ReaderNavigation 
                    currentChapterIndex={currentIndex}
                    totalChapters={story?.totalChapters || 0}
                    hasPrevious={hasPrevious}
                    hasNext={hasNext}
                    onPrevious={() => navigateToChapter(currentIndex - 1)}
                    onNext={() => navigateToChapter(currentIndex + 1)}
                />
            </View>
        </ScreenContainer>
    );
}

const styles = StyleSheet.create({
    container: {
        flex: 1,
    },
    center: {
        flex: 1,
        justifyContent: 'center',
        alignItems: 'center',
    },
});
