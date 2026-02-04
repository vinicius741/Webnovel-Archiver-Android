import { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import { DeviceEventEmitter, Platform } from 'react-native';
import Constants from 'expo-constants';
import { ttsStateManager, TTS_STATE_EVENTS, TTSState } from '../services/TTSStateManager';
import { TTSSettings } from '../services/StorageService';
import { loadNotifee, type NotifeeModule } from '../services/NotifeeTypes';
import type { Event } from '@notifee/react-native/dist/types/Notification';

export const useTTS = (options?: { onFinish?: () => void }) => {
    const onFinishRef = useRef(options?.onFinish);

    useEffect(() => {
        onFinishRef.current = options?.onFinish;
    }, [options?.onFinish]);
    // UI state synchronized from TTSStateManager
    const [isSpeaking, setIsSpeaking] = useState(false);
    const [isPaused, setIsPaused] = useState(false);
    const [chunks, setChunks] = useState<string[]>([]);
    const [currentChunkIndex, setCurrentChunkIndex] = useState(0);

    // Local UI state
    const [ttsSettings, setTtsSettings] = useState<TTSSettings>({ pitch: 1.0, rate: 1.0, chunkSize: 500 });
    const [isSettingsVisible, setIsSettingsVisible] = useState(false);
    const [isControllerVisible, setIsControllerVisible] = useState(false);

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

    useEffect(() => {
        // Load initial settings
        const settings = ttsStateManager.getSettings();
        if (settings) {
            setTtsSettings(settings);
        }

        // Sync initial state
        syncState(ttsStateManager.getState());

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
            syncState
        );

        // Also handle foreground notification events (for when app is in foreground)
        let unsubscribeNotifee: (() => void) | undefined;

        if (Platform.OS === 'android' && Constants.executionEnvironment !== 'storeClient') {
            try {
                const notifee = loadNotifee();
                if (notifee) {
                    unsubscribeNotifee = notifee.default.onForegroundEvent((event: Event) => {
                        if (event.type === notifee.EventType.ACTION_PRESS && event.detail.pressAction) {
                            const actionId = event.detail.pressAction.id;
                            switch (actionId) {
                                case 'tts_play':
                                    ttsStateManager.resume();
                                    break;
                                case 'tts_pause':
                                    ttsStateManager.pause();
                                    break;
                                case 'tts_next':
                                    ttsStateManager.next();
                                    break;
                                case 'tts_prev':
                                    ttsStateManager.previous();
                                    break;
                                case 'tts_stop':
                                    ttsStateManager.stop();
                                    break;
                            }
                        }
                    });
                }
            } catch (e) {
                // Notifee not available
            }
        }

        return () => {
            subscription.remove();
            if (unsubscribeNotifee) unsubscribeNotifee();
            ttsStateManager.setOnFinishCallback(null);
        };
    }, [options?.onFinish, syncState]);

    const handleSettingsChange = useCallback(async (newSettings: TTSSettings) => {
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

    const toggleSpeech = useCallback(async (newChunks: string[], title: string = 'Reading') => {
        const currentState = ttsStateManager.getState();
        const shouldStop = currentState?.isSpeaking || currentState?.isPaused || false;
        if (shouldStop) {
            await stopSpeech();
        } else {
            if (!newChunks || newChunks.length === 0) return;
            ttsStateManager.start(newChunks, title);
            setIsControllerVisible(true);
        }
    }, [stopSpeech]);

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
