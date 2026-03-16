import { useState, useEffect, useCallback, useRef } from "react";
import { DeviceEventEmitter, Platform } from "react-native";
import Constants from "expo-constants";
import {
  ttsStateManager,
  TTS_STATE_EVENTS,
  TTSState,
} from "../services/TTSStateManager";
import type { TTSSettings } from "../types";
import { loadNotifee } from "../services/NotifeeTypes";
import type { Event } from "@notifee/react-native/dist/types/Notification";

interface TTSPlaybackContext {
  storyId?: string;
  chapterId?: string;
  chapterTitle?: string;
}

const DEFAULT_TTS_SETTINGS: TTSSettings = {
  pitch: 1.0,
  rate: 1.0,
  chunkSize: 500,
};

const areTtsSettingsEqual = (
  left: TTSSettings,
  right: TTSSettings,
): boolean =>
  left.pitch === right.pitch &&
  left.rate === right.rate &&
  left.chunkSize === right.chunkSize &&
  left.voiceIdentifier === right.voiceIdentifier;

const getInitialControllerVisibility = (state: TTSState | null): boolean => {
  if (!state) return false;
  if (state.isSpeaking) return true;
  if (!state.isSpeaking && !state.isPaused) return false;
  return false;
};

const TTS_NOTIFICATION_ACTION_HANDLERS = {
  tts_play: () => ttsStateManager.resume(),
  tts_pause: () => ttsStateManager.pause(),
  tts_next: () => ttsStateManager.next(),
  tts_prev: () => ttsStateManager.previous(),
  tts_stop: () => ttsStateManager.stop(),
} as const;

type TTSNotificationActionId = keyof typeof TTS_NOTIFICATION_ACTION_HANDLERS;

const isTTSNotificationAction = (
  actionId: string,
): actionId is TTSNotificationActionId =>
  Object.prototype.hasOwnProperty.call(
    TTS_NOTIFICATION_ACTION_HANDLERS,
    actionId,
  );

export const useTTS = (options?: { onFinish?: () => void }) => {
  const onFinishRef = useRef(options?.onFinish);
  const initialTtsSettings = ttsStateManager.getSettings() || DEFAULT_TTS_SETTINGS;
  const initialTtsSettingsRef = useRef<TTSSettings>(initialTtsSettings);
  const latestTtsSettingsRef = useRef<TTSSettings>(initialTtsSettings);

  useEffect(() => {
    onFinishRef.current = options?.onFinish;
  }, [options?.onFinish]);
  // UI state synchronized from TTSStateManager
  const [isSpeaking, setIsSpeaking] = useState(
    () => ttsStateManager.getState()?.isSpeaking ?? false,
  );
  const [isPaused, setIsPaused] = useState(
    () => ttsStateManager.getState()?.isPaused ?? false,
  );
  const [chunks, setChunks] = useState<string[]>(
    () => ttsStateManager.getState()?.chunks ?? [],
  );
  const [currentChunkIndex, setCurrentChunkIndex] = useState(
    () => ttsStateManager.getState()?.currentChunkIndex ?? 0,
  );

  // Local UI state
  const [ttsSettings, setTtsSettings] = useState<TTSSettings>(initialTtsSettings);
  const [isSettingsVisible, setIsSettingsVisible] = useState(false);
  const [isControllerVisible, setIsControllerVisible] = useState(
    () => getInitialControllerVisibility(ttsStateManager.getState()),
  );

  useEffect(() => {
    latestTtsSettingsRef.current = ttsSettings;
  }, [ttsSettings]);

  // Sync state from TTSStateManager
  const syncState = useCallback((state: TTSState | null) => {
    if (!state) {
      setIsSpeaking(false);
      setIsPaused(false);
      setChunks([]);
      setCurrentChunkIndex(0);
      setIsControllerVisible(false);
      return;
    }
    setIsSpeaking(state.isSpeaking);
    setIsPaused(state.isPaused);
    setChunks(state.chunks);
    setCurrentChunkIndex(state.currentChunkIndex);

    // Update controller visibility based on speaking state
    if (state.isSpeaking) {
      setIsControllerVisible(true);
    } else if (!state.isSpeaking && !state.isPaused) {
      // Only hide controller when fully stopped (not just paused)
      setIsControllerVisible(false);
    }
  }, []);

  const handleForegroundNotificationEvent = useCallback((event: Event) => {
    const actionId = event.detail.pressAction?.id;
    if (!actionId || !isTTSNotificationAction(actionId)) {
      return;
    }
    void TTS_NOTIFICATION_ACTION_HANDLERS[actionId]();
  }, []);

  useEffect(() => {
    let mounted = true;

    const initializeTtsState = async () => {
      await ttsStateManager.initialize();
      if (!mounted) return;

      const initializedSettings = ttsStateManager.getSettings();
      const currentSettings = latestTtsSettingsRef.current;
      const initialSettings = initialTtsSettingsRef.current;
      const isStillUsingInitialSettings = areTtsSettingsEqual(
        currentSettings,
        initialSettings,
      );
      const hasPersistedSettingsToHydrate = !areTtsSettingsEqual(
        initializedSettings,
        initialSettings,
      );

      if (!isStillUsingInitialSettings || !hasPersistedSettingsToHydrate) {
        return;
      }

      latestTtsSettingsRef.current = initializedSettings;
      setTtsSettings(initializedSettings);
    };

    void initializeTtsState();

    // Set up finish callback - always call this, even if onFinish is undefined
    const finishWrapper = () => {
      if (onFinishRef.current) {
        onFinishRef.current();
      }
    };
    ttsStateManager.setOnFinishCallback(finishWrapper);

    // Subscribe to state changes from TTSStateManager
    const subscription = DeviceEventEmitter.addListener(
      TTS_STATE_EVENTS.STATE_CHANGED,
      syncState,
    );

    // Also handle foreground notification events (for when app is in foreground)
    let unsubscribeNotifee: (() => void) | undefined;

    if (
      Platform.OS === "android" &&
      (Constants.executionEnvironment as string) !== "storeClient"
    ) {
      try {
        const notifee = loadNotifee();
        if (notifee) {
          unsubscribeNotifee = notifee.default.onForegroundEvent(
            (event: Event) => {
              if (
                event.type === notifee.EventType.ACTION_PRESS &&
                event.detail.pressAction
              ) {
                handleForegroundNotificationEvent(event);
              }
            },
          );
        }
      } catch {
        // Notifee not available
      }
    }

    return () => {
      mounted = false;
      subscription.remove();
      if (unsubscribeNotifee) unsubscribeNotifee();
      ttsStateManager.setOnFinishCallback(null);
    };
    // Note: options?.onFinish is intentionally omitted from deps because we use
    // onFinishRef to always get the latest callback without triggering effect re-runs.
    // The ref is updated in a separate useEffect above (lines 23-25).
  }, [handleForegroundNotificationEvent, syncState]);

  const handleSettingsChange = useCallback(async (newSettings: TTSSettings) => {
    latestTtsSettingsRef.current = newSettings;
    setTtsSettings(newSettings);
    await ttsStateManager.updateSettings(newSettings);
  }, []);

  const stopSpeech = useCallback(async () => {
    await ttsStateManager.stop();
  }, []);

  const handlePlayPause = useCallback(async () => {
    await ttsStateManager.playPause();
  }, []);

  const handleNextChunk = useCallback(async () => {
    await ttsStateManager.next();
  }, []);

  const handlePreviousChunk = useCallback(async () => {
    await ttsStateManager.previous();
  }, []);

  const toggleSpeech = useCallback(
    async (
      newChunks: string[],
      title: string = "Reading",
      context?: TTSPlaybackContext,
    ) => {
      const currentState = ttsStateManager.getState();
      const shouldStop =
        currentState?.isSpeaking || currentState?.isPaused || false;
      if (shouldStop) {
        await stopSpeech();
      } else {
        if (!newChunks || newChunks.length === 0) return;
        await ttsStateManager.start({
          chunks: newChunks,
          title,
          storyId: context?.storyId || "",
          chapterId: context?.chapterId || "",
          chapterTitle: context?.chapterTitle || title,
        });
        setIsControllerVisible(true);
      }
    },
    [stopSpeech],
  );

  return {
    isSpeaking,
    isPaused,
    chunks,
    currentChunkIndex,
    ttsSettings,
    isSettingsVisible,
    isControllerVisible,
    setIsSettingsVisible,
    setIsControllerVisible,
    toggleSpeech,
    stopSpeech,
    handlePlayPause,
    handleNextChunk,
    handlePreviousChunk,
    handleSettingsChange,
  };
};
