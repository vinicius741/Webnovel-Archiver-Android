import React, { useEffect, useState, useMemo, useRef } from 'react';
import { View, StyleSheet, ActivityIndicator } from 'react-native';
import { useLocalSearchParams, Stack, useRouter } from 'expo-router';
import { WebView } from 'react-native-webview';
import { IconButton, useTheme, Snackbar } from 'react-native-paper';
import * as Clipboard from 'expo-clipboard';
import { Story, Chapter } from '../../../src/types';
import { ScreenContainer } from '../../../src/components/ScreenContainer';
import { sanitizeTitle } from '../../../src/utils/stringUtils';
import { TTSSettingsModal } from '../../../src/components/TTSSettingsModal';
import { TTSController } from '../../../src/components/TTSController';
import { useTTS } from '../../../src/hooks/useTTS';
import { prepareTTSContent, extractPlainText } from '../../../src/utils/htmlUtils';
import { ReaderNavigation } from '../../../src/components/ReaderNavigation';
import { ReaderContent } from '../../../src/components/ReaderContent';
import { useReaderContent } from '../../../src/hooks/useReaderContent';
import { useReaderNavigation } from '../../../src/hooks/useReaderNavigation';
import { useWebViewHighlight } from '../../../src/hooks/useWebViewHighlight';

export default function ReaderScreen() {
    const { storyId, chapterId, autoplay } = useLocalSearchParams<{ storyId: string; chapterId: string; autoplay?: string }>();
    const theme = useTheme();
    const router = useRouter();
    const webViewRef = useRef<WebView>(null);
    
    const [copyFeedbackVisible, setCopyFeedbackVisible] = useState(false);

    const {
        story,
        chapter,
        content,
        loading,
        loadData,
        markAsRead,
        currentIndex,
        isLastRead,
    } = useReaderContent(storyId, chapterId);

    const {
        hasNext,
        hasPrevious,
        navigateToChapter,
    } = useReaderNavigation(story, chapter, currentIndex);

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
    } = useTTS({
        onFinish: () => {
            if (hasNext) {
                markAsRead();
                navigateToChapter(currentIndex + 1, { autoplay: 'true' });
            }
        }
    });

    const { processedHtml: processedContent, chunks: ttsChunks } = useMemo(() => {
        return prepareTTSContent(content, ttsSettings.chunkSize);
    }, [content, ttsSettings.chunkSize]);

    useWebViewHighlight(webViewRef as React.RefObject<WebView>, currentChunkIndex, isControllerVisible);

    useEffect(() => {
        loadData();
    }, [storyId, chapterId]);

    useEffect(() => {
        stopSpeech();
    }, [chapterId]);

    useEffect(() => {
        if (autoplay === 'true' && !loading && content && ttsChunks.length > 0 && !isSpeaking) {
            const timer = setTimeout(() => {
                toggleSpeech(ttsChunks, chapter ? sanitizeTitle(chapter.title) : 'Reading');
                router.setParams({ autoplay: undefined }); 
            }, 500); 
            return () => clearTimeout(timer);
        }
    }, [autoplay, loading, content, ttsChunks, isSpeaking]);

    const handleCopy = async () => {
        if (!content) return;
        const plainText = extractPlainText(content);
        await Clipboard.setStringAsync(plainText);
        setCopyFeedbackVisible(true);
    };

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
                                onPress={() => toggleSpeech(ttsChunks, chapter ? sanitizeTitle(chapter.title) : 'Reading')}
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
                <ReaderContent 
                    webViewRef={webViewRef as React.RefObject<WebView>}
                    processedContent={processedContent}
                />
                
                <ReaderNavigation 
                    currentChapterIndex={currentIndex}
                    totalChapters={story?.totalChapters || 0}
                    hasPrevious={hasPrevious}
                    hasNext={hasNext}
                    onPrevious={() => navigateToChapter(currentIndex - 1)}
                    onNext={() => navigateToChapter(currentIndex + 1)}
                    onCopy={handleCopy}
                />
            </View>

            <Snackbar
                visible={copyFeedbackVisible}
                onDismiss={() => setCopyFeedbackVisible(false)}
                duration={2000}
                style={{ backgroundColor: theme.colors.inverseSurface }}
                action={{
                    label: 'OK',
                    onPress: () => setCopyFeedbackVisible(false),
                }}
            >
                Chapter copied to clipboard
            </Snackbar>
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
