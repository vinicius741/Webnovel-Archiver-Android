import React, { useEffect, useState, useMemo, useRef, useCallback } from 'react';
import { View, StyleSheet, ActivityIndicator } from 'react-native';
import { useLocalSearchParams, useRouter, Stack } from 'expo-router';
import { WebView } from 'react-native-webview';
import { IconButton, useTheme, Snackbar } from 'react-native-paper';
import * as Clipboard from 'expo-clipboard';
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

    const currentTtsChunksRef = useRef<string[]>([]);
    const currentChapterTitleRef = useRef<string>('');
    const isSpeakingRef = useRef(false);
    const isLastReadRef = useRef(false);
    const themeColorsRef = useRef(theme.colors);

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

    const handleTTSFinish = useCallback(() => {
        if (hasNext) {
            markAsRead();
            navigateToChapter(currentIndex + 1, { autoplay: 'true' });
        }
    }, [hasNext, markAsRead, navigateToChapter, currentIndex]);

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
        onFinish: handleTTSFinish
    });

    const { processedHtml: processedContent, chunks: ttsChunks } = useMemo(() => {
        return prepareTTSContent(content, ttsSettings.chunkSize);
    }, [content, ttsSettings.chunkSize]);

    useWebViewHighlight(webViewRef as React.RefObject<WebView>, currentChunkIndex, isControllerVisible);

    useEffect(() => {
        currentTtsChunksRef.current = ttsChunks;
    }, [ttsChunks]);

    useEffect(() => {
        currentChapterTitleRef.current = chapter ? sanitizeTitle(chapter.title) : 'Reading';
    }, [chapter]);

    useEffect(() => {
        isSpeakingRef.current = isSpeaking;
    }, [isSpeaking]);

    useEffect(() => {
        isLastReadRef.current = isLastRead;
    }, [isLastRead]);

    useEffect(() => {
        themeColorsRef.current = theme.colors;
    }, [theme]);

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
        await Clipboard.setStringAsync(extractPlainText(content));
        setCopyFeedbackVisible(true);
    };

    const handleToggleSpeech = useCallback(() => {
        toggleSpeech(currentTtsChunksRef.current, currentChapterTitleRef.current);
    }, [toggleSpeech]);

    const HeaderRight = useMemo(() => () => (
        <View style={{ flexDirection: 'row' }}>
            <IconButton
                icon={isSpeakingRef.current ? "stop" : "volume-high"}
                iconColor={isSpeakingRef.current ? themeColorsRef.current.error : undefined}
                onPress={handleToggleSpeech}
            />

            <IconButton
                icon="cog-outline"
                onPress={() => setIsSettingsVisible(true)}
            />

            <IconButton
                icon={isLastReadRef.current ? "bookmark" : "bookmark-outline"}
                iconColor={isLastReadRef.current ? themeColorsRef.current.primary : undefined}
                onPress={markAsRead}
            />
        </View>
    ), [handleToggleSpeech, markAsRead]);

    const screenOptions = useMemo(() => ({
        title: chapter ? sanitizeTitle(chapter.title) : 'Reader',
        headerRight: HeaderRight
    }), [chapter, HeaderRight]);

    const handleDismissSettings = useCallback(() => {
        setIsSettingsVisible(false);
    }, []);

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
                options={screenOptions}
            />

            <TTSSettingsModal
                visible={isSettingsVisible}
                onDismiss={handleDismissSettings}
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
