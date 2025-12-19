import React, { useEffect, useState, useMemo } from 'react';
import { View, StyleSheet, ActivityIndicator } from 'react-native';
import { useLocalSearchParams, Stack, useRouter } from 'expo-router';
import { WebView } from 'react-native-webview';
import { IconButton, useTheme, Text, Appbar } from 'react-native-paper';
import { storageService, TTSSettings } from '../../../src/services/StorageService';
import { readChapterFile } from '../../../src/services/storage/fileSystem';
import { Story, Chapter } from '../../../src/types';
import { ScreenContainer } from '../../../src/components/ScreenContainer';
import { useAppAlert } from '../../../src/context/AlertContext';
import { sanitizeTitle } from '../../../src/utils/stringUtils';
import * as Speech from 'expo-speech';
import { extractPlainText } from '../../../src/utils/htmlUtils';
import { TTSSettingsModal } from '../../../src/components/TTSSettingsModal';


export default function ReaderScreen() {
    const { storyId, chapterId } = useLocalSearchParams<{ storyId: string; chapterId: string }>();
    const theme = useTheme();
    const router = useRouter();
    const { showAlert } = useAppAlert();
    
    const [story, setStory] = useState<Story | null>(null);
    const [chapter, setChapter] = useState<Chapter | null>(null);
    const [content, setContent] = useState<string>('');
    const [loading, setLoading] = useState(true);
    const [isSpeaking, setIsSpeaking] = useState(false);
    const [currentChunkIndex, setCurrentChunkIndex] = useState(0);
    const [ttsSettings, setTtsSettings] = useState<TTSSettings>({ pitch: 1.0, rate: 1.0 });
    const [isSettingsVisible, setIsSettingsVisible] = useState(false);

    const loadTtsSettings = async () => {
        const settings = await storageService.getTTSSettings();
        setTtsSettings(settings);
    };

    const handleSettingsChange = async (newSettings: TTSSettings) => {
        setTtsSettings(newSettings);
        await storageService.saveTTSSettings(newSettings);
    };



    useEffect(() => {
        loadData();
        loadTtsSettings();
    }, [storyId, chapterId]);

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

    useEffect(() => {
        return () => {
            Speech.stop();
        };
    }, []);

    useEffect(() => {
        Speech.stop();
        setIsSpeaking(false);
    }, [chapterId]);

    const toggleSpeech = async () => {
        if (isSpeaking) {
            Speech.stop();
            setIsSpeaking(false);
            setCurrentChunkIndex(0);
        } else {
            const plainText = extractPlainText(content);
            if (!plainText) return;

            // Split text into chunks of ~3500 characters
            const chunks: string[] = [];
            let currentText = plainText;
            while (currentText.length > 0) {
                if (currentText.length <= 3500) {
                    chunks.push(currentText);
                    break;
                }
                
                // Find a good splitting point (period followed by space)
                let splitIndex = currentText.lastIndexOf('. ', 3500);
                if (splitIndex === -1) splitIndex = currentText.lastIndexOf(' ', 3500);
                if (splitIndex === -1) splitIndex = 3500;

                chunks.push(currentText.substring(0, splitIndex + 1).trim());
                currentText = currentText.substring(splitIndex + 1).trim();
            }

            if (chunks.length === 0) return;

            setIsSpeaking(true);
            setCurrentChunkIndex(0);
            
            const speakChunk = (index: number) => {
                if (index >= chunks.length) {
                    setIsSpeaking(false);
                    setCurrentChunkIndex(0);
                    return;
                }

                setCurrentChunkIndex(index);
                Speech.speak(chunks[index], {
                    pitch: ttsSettings.pitch,
                    rate: ttsSettings.rate,
                    voice: ttsSettings.voiceIdentifier,
                    onDone: () => speakChunk(index + 1),
                    onStopped: () => {
                        setIsSpeaking(false);
                        setCurrentChunkIndex(0);
                    },
                    onError: (error) => {
                        console.error('Speech error:', error);
                        setIsSpeaking(false);
                        setCurrentChunkIndex(0);
                    },
                });
            };

            speakChunk(0);
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
                                onPress={toggleSpeech}
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

            
            <View style={styles.container}>
                <WebView
                    originWhitelist={['*']}
                    source={{ html: htmlContent }}
                    style={{ backgroundColor: theme.colors.surface }}
                />
                
                <Appbar style={[styles.bottomBar, { backgroundColor: theme.colors.elevation.level2 }]}>
                    <Appbar.Action 
                        icon="chevron-left" 
                        disabled={!hasPrevious} 
                        onPress={() => navigateToChapter(currentIndex - 1)} 
                    />
                    <View style={styles.spacer} />
                    <Text variant="labelLarge" style={{ color: theme.colors.onSurfaceVariant }}>
                        {currentIndex + 1} / {story?.totalChapters}
                    </Text>
                    <View style={styles.spacer} />
                    <Appbar.Action 
                        icon="chevron-right" 
                        disabled={!hasNext} 
                        onPress={() => navigateToChapter(currentIndex + 1)} 
                    />
                </Appbar>
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
    bottomBar: {
        justifyContent: 'space-between',
        paddingHorizontal: 8,
    },
    spacer: {
        flex: 1,
    }
});
