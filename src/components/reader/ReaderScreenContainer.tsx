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
import { useScreenLayout } from "../../hooks/useScreenLayout";
import { useReaderScreenController } from "../../hooks/reader/useReaderScreenController";
import { ReaderHeaderRight } from "./ReaderHeaderRight";

interface ReaderScreenContainerProps {
  storyId: string;
  chapterId: string;
  autoplay?: string;
  resumeSession?: string;
}

type ScreenLayout = ReturnType<typeof useScreenLayout> & {
  widthClass?: "compact" | "medium" | "expanded";
  heightClass?: "compact" | "medium" | "expanded";
  isCompactHeight?: boolean;
};

const READER_COLUMN_MAX_WIDTH = 800;

export const ReaderScreenContainer: React.FC<ReaderScreenContainerProps> = ({
  storyId,
  chapterId,
  autoplay,
  resumeSession,
}) => {
  const layout = useScreenLayout() as ScreenLayout;
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

  const screenWidth = layout.screenWidth || 0;
  const derivedWidthClass =
    layout.widthClass ||
    (screenWidth >= 960
      ? "expanded"
      : screenWidth >= 600
        ? "medium"
        : "compact");
  const isCompactHeight =
    layout.isCompactHeight ||
    layout.heightClass === "compact" ||
    (layout.screenHeight || 0) < 520;

  const shellPadding = useMemo(() => {
    if (derivedWidthClass === "expanded") {
      return 40;
    }
    if (derivedWidthClass === "medium") {
      return 28;
    }
    return 16;
  }, [derivedWidthClass]);

  const readerColumnWidth = useMemo(() => {
    if (screenWidth <= 0) {
      return READER_COLUMN_MAX_WIDTH;
    }

    return Math.max(
      0,
      Math.min(READER_COLUMN_MAX_WIDTH, screenWidth - shellPadding * 2),
    );
  }, [screenWidth, shellPadding]);

  const readerBodyPadding = derivedWidthClass === "expanded" ? 28 : 20;
  const readerBottomPadding = isControllerVisible
    ? isCompactHeight
      ? 176
      : 208
    : 112;
  const ttsBottomOffset = isCompactHeight ? 84 : 96;

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
        maxWidth={readerColumnWidth}
        horizontalPadding={shellPadding}
        bottomOffset={ttsBottomOffset}
        compactHeight={isCompactHeight}
        onPlayPause={handlePlayPause}
        onStop={stopSpeech}
        onNext={handleNextChunk}
        onPrevious={handlePreviousChunk}
      />

      <View style={styles.container}>
        <View
          style={[
            styles.readerContentShell,
            { paddingHorizontal: shellPadding },
          ]}
        >
          <ReaderContent
            webViewRef={webViewRef as React.RefObject<WebView>}
            processedContent={processedContent}
            maxWidth={readerColumnWidth}
            contentPadding={readerBodyPadding}
            bottomPadding={readerBottomPadding}
          />
        </View>

        <ReaderNavigation
          currentChapterIndex={currentIndex}
          totalChapters={story?.totalChapters || 0}
          hasPrevious={hasPrevious}
          hasNext={hasNext}
          maxWidth={readerColumnWidth}
          horizontalPadding={shellPadding}
          compactHeight={isCompactHeight}
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
  readerContentShell: {
    flex: 1,
  },
  center: {
    flex: 1,
    justifyContent: "center",
    alignItems: "center",
  },
});
