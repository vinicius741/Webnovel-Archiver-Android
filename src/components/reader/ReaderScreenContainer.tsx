import React, { useMemo } from "react";
import { ActivityIndicator, StyleSheet, View } from "react-native";
import { Stack } from "expo-router";
import { Snackbar } from "react-native-paper";
import { WebView } from "react-native-webview";

import { ReaderNavigation } from "../ReaderNavigation";
import { ReaderContent } from "../ReaderContent";
import { ScreenContainer } from "../ScreenContainer";
import { TTSController } from "../TTSController";
import { TTSSettingsModal } from "../TTSSettingsModal";
import { useReaderScreenController } from "../../hooks/reader/useReaderScreenController";
import { ReaderHeaderRight } from "./ReaderHeaderRight";

interface ReaderScreenContainerProps {
  storyId: string;
  chapterId: string;
  autoplay?: string;
  resumeSession?: string;
}

export const ReaderScreenContainer: React.FC<ReaderScreenContainerProps> = ({
  storyId,
  chapterId,
  autoplay,
  resumeSession,
}) => {
  const {
    theme,
    webViewRef,
    loading,
    story,
    content,
    chapterTitle,
    currentIndex,
    hasNext,
    hasPrevious,
    isLastRead,
    processedContent,
    cleanupRulesLoaded,
    isSpeaking,
    isPaused,
    chunks,
    currentChunkIndex,
    ttsSettings,
    isSettingsVisible,
    isControllerVisible,
    copyFeedbackVisible,
    setCopyFeedbackVisible,
    handleToggleSpeech,
    handleCopy,
    markAsRead,
    navigateToChapter,
    setIsSettingsVisible,
    stopSpeech,
    handlePlayPause,
    handleNextChunk,
    handlePreviousChunk,
    handleSettingsChange,
  } = useReaderScreenController({
    storyId,
    chapterId,
    autoplay,
    resumeSession,
  });

  const screenOptions = useMemo(
    () => ({
      title: chapterTitle || "Reader",
      headerRight: () => (
        <ReaderHeaderRight
          isSpeaking={isSpeaking}
          isLastRead={isLastRead}
          cleanupRulesLoaded={cleanupRulesLoaded}
          onToggleSpeech={handleToggleSpeech}
          onOpenSettings={() => setIsSettingsVisible(true)}
          onMarkAsRead={markAsRead}
        />
      ),
    }),
    [
      chapterTitle,
      isSpeaking,
      isLastRead,
      cleanupRulesLoaded,
      handleToggleSpeech,
      setIsSettingsVisible,
      markAsRead,
    ],
  );

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
    <ScreenContainer edges={["bottom", "left", "right"]}>
      <Stack.Screen options={screenOptions} />

      <TTSSettingsModal
        visible={isSettingsVisible}
        onDismiss={() => setIsSettingsVisible(false)}
        settings={ttsSettings}
        onSettingsChange={handleSettingsChange}
      />

      <TTSController
        visible={isControllerVisible}
        _isSpeaking={isSpeaking}
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
          label: "OK",
          onPress: () => setCopyFeedbackVisible(false),
        }}
      >
        Chapter copied to clipboard
      </Snackbar>
    </ScreenContainer>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
  },
  center: {
    flex: 1,
    justifyContent: "center",
    alignItems: "center",
  },
});
