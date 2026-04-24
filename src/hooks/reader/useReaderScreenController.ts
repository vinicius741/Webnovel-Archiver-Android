import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useRouter } from "expo-router";
import * as Clipboard from "expo-clipboard";
import { useTheme } from "react-native-paper";
import { WebView } from "react-native-webview";

import {
  extractFormattedText,
  prepareTTSContent,
} from "../../utils/htmlUtils";
import { sanitizeTitle } from "../../utils/stringUtils";
import { useReaderContent } from "../useReaderContent";
import { useReaderNavigation } from "../useReaderNavigation";
import { useTTS } from "../useTTS";
import { useWebViewHighlight } from "../useWebViewHighlight";
import { ttsStateManager } from "../../services/TTSStateManager";
import { storageService } from "../../services/StorageService";
import { RegexCleanupRule } from "../../types";

interface UseReaderScreenControllerParams {
  storyId: string;
  chapterId: string;
  autoplay?: string;
  resumeSession?: string;
}

export const useReaderScreenController = ({
  storyId,
  chapterId,
  autoplay,
  resumeSession,
}: UseReaderScreenControllerParams) => {
  const theme = useTheme();
  const router = useRouter();
  const webViewRef = useRef<WebView>(null);
  const [copyFeedbackVisible, setCopyFeedbackVisible] = useState(false);
  const [regexCleanupRules, setRegexCleanupRules] = useState<
    RegexCleanupRule[]
  >([]);
  const [cleanupRulesLoaded, setCleanupRulesLoaded] = useState(false);

  const decodedChapterId = useMemo(() => {
    try {
      return decodeURIComponent(chapterId);
    } catch {
      return chapterId;
    }
  }, [chapterId]);

  const {
    story,
    chapter,
    content,
    loading,
    redirectPath,
    loadData,
    markAsRead,
    currentIndex,
    isLastRead,
  } = useReaderContent(storyId, chapterId);

  const { hasNext, hasPrevious, navigateToChapter } = useReaderNavigation(
    story,
    chapter,
    currentIndex,
  );

  const chapterTitle = chapter ? sanitizeTitle(chapter.title) : "Reading";

  const handleTTSFinish = useCallback(() => {
    if (!hasNext) return;
    markAsRead().catch((error) => {
      console.error("[ReaderScreen] Failed to mark chapter as read:", error);
    });
    navigateToChapter(currentIndex + 1, { autoplay: "true" });
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
    startSpeechAt,
    stopSpeech,
    handlePlayPause,
    handleNextChunk,
    handlePreviousChunk,
    handleSettingsChange,
  } = useTTS({
    onFinish: handleTTSFinish,
  });

  const { processedHtml: processedContent, chunks: ttsChunks } = useMemo(() => {
    return prepareTTSContent(content, ttsSettings.chunkSize, regexCleanupRules);
  }, [content, ttsSettings.chunkSize, regexCleanupRules]);

  useWebViewHighlight(
    webViewRef as React.RefObject<WebView>,
    currentChunkIndex,
    isControllerVisible,
  );

  useEffect(() => {
    void loadData();
  }, [storyId, chapterId, loadData]);

  useEffect(() => {
    let mounted = true;

    const loadCleanupRules = async () => {
      try {
        const rules = await storageService.getRegexCleanupRules();
        if (mounted) {
          setRegexCleanupRules(rules);
        }
      } catch (error) {
        console.error("Failed to load regex cleanup rules for TTS", error);
        if (mounted) {
          setRegexCleanupRules([]);
        }
      } finally {
        if (mounted) {
          setCleanupRulesLoaded(true);
        }
      }
    };

    void loadCleanupRules();
    return () => {
      mounted = false;
    };
  }, []);

  useEffect(() => {
    if (!redirectPath) return;
    router.replace(redirectPath);
  }, [redirectPath, router]);

  useEffect(() => {
    if (
      !cleanupRulesLoaded ||
      loading ||
      !story ||
      !chapter ||
      ttsChunks.length === 0
    ) {
      return;
    }

    if (resumeSession !== "true" && autoplay === "true") return;

    void ttsStateManager.restoreForChapter({
      chunks: ttsChunks,
      title: chapterTitle,
      storyId,
      chapterId: decodedChapterId,
      chapterTitle,
    });
  }, [
    cleanupRulesLoaded,
    loading,
    story,
    chapter,
    ttsChunks,
    chapterTitle,
    storyId,
    decodedChapterId,
    resumeSession,
    autoplay,
  ]);

  useEffect(() => {
    if (!cleanupRulesLoaded) return;
    if (
      autoplay === "true" &&
      !loading &&
      content &&
      ttsChunks.length > 0 &&
      !isSpeaking
    ) {
      const timer = setTimeout(() => {
        void toggleSpeech(ttsChunks, chapterTitle, {
          storyId,
          chapterId: decodedChapterId,
          chapterTitle,
        });
        router.setParams({ autoplay: undefined });
      }, 500);
      return () => clearTimeout(timer);
    }
  }, [
    cleanupRulesLoaded,
    autoplay,
    loading,
    content,
    ttsChunks,
    isSpeaking,
    toggleSpeech,
    chapterTitle,
    storyId,
    decodedChapterId,
    router,
  ]);

  const handleToggleSpeech = useCallback(() => {
    if (!cleanupRulesLoaded) return;

    void toggleSpeech(ttsChunks, chapterTitle, {
      storyId,
      chapterId: decodedChapterId,
      chapterTitle,
    });
  }, [
    cleanupRulesLoaded,
    toggleSpeech,
    ttsChunks,
    chapterTitle,
    storyId,
    decodedChapterId,
  ]);

  const handleTTSUnitPress = useCallback(
    (index: number) => {
      if (!cleanupRulesLoaded) return;
      void startSpeechAt(ttsChunks, chapterTitle, index, {
        storyId,
        chapterId: decodedChapterId,
        chapterTitle,
      });
    },
    [
      cleanupRulesLoaded,
      startSpeechAt,
      ttsChunks,
      chapterTitle,
      storyId,
      decodedChapterId,
    ],
  );

  const handleCopy = useCallback(async () => {
    if (!content) return;
    await Clipboard.setStringAsync(extractFormattedText(content));
    setCopyFeedbackVisible(true);
  }, [content]);

  return {
    theme,
    webViewRef,
    loading,
    story,
    chapter,
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
    handleTTSUnitPress,
    handleCopy,
    markAsRead,
    navigateToChapter,
    setIsSettingsVisible,
    stopSpeech,
    handlePlayPause,
    handleNextChunk,
    handlePreviousChunk,
    handleSettingsChange,
  };
};
