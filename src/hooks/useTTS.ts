import { useState, useEffect, useCallback } from 'react';
import { DeviceEventEmitter, Platform } from 'react-native';
import { ttsStateManager, TTS_STATE_EVENTS, TTSState } from '../services/TTSStateManager';
import { TTSSettings } from '../services/StorageService';

export const useTTS = (options?: { onFinish?: () => void }) => {
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
    const syncState = useCallback((state: TTSState) => {
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

        // Set up finish callback
        ttsStateManager.setOnFinishCallback(options?.onFinish || null);

        // Subscribe to state changes from TTSStateManager
        const subscription = DeviceEventEmitter.addListener(
            TTS_STATE_EVENTS.STATE_CHANGED,
            syncState
        );

        // Also handle foreground notification events (for when app is in foreground)
        let unsubscribeNotifee: (() => void) | undefined;

        if (Platform.OS === 'android') {
            try {
                const notifee = require('@notifee/react-native').default;
                const { EventType } = require('@notifee/react-native');

                unsubscribeNotifee = notifee.onForegroundEvent(({ type, detail }: any) => {
                    if (type === EventType.ACTION_PRESS && detail.pressAction) {
                        const actionId = detail.pressAction.id;
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

    const handleSettingsChange = async (newSettings: TTSSettings) => {
        setTtsSettings(newSettings);
        await ttsStateManager.updateSettings(newSettings);
    };

    const stopSpeech = async () => {
        await ttsStateManager.stop();
    };

    const handlePlayPause = async () => {
        await ttsStateManager.playPause();
    };

    const handleNextChunk = async () => {
        await ttsStateManager.next();
    };

    const handlePreviousChunk = async () => {
        await ttsStateManager.previous();
    };

    const toggleSpeech = async (newChunks: string[], title: string = 'Reading') => {
        if (isSpeaking || isControllerVisible) {
            await stopSpeech();
        } else {
            if (!newChunks || newChunks.length === 0) return;
            ttsStateManager.start(newChunks, title);
            setIsControllerVisible(true);
        }
    };

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
