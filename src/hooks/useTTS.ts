import { useState, useEffect, useRef } from 'react';
import { DeviceEventEmitter, Platform } from 'react-native';
import * as Speech from 'expo-speech';
import { storageService, TTSSettings } from '../services/StorageService';
import { ttsNotificationService, TTS_EVENTS } from '../services/TTSNotificationService';

export const useTTS = (options?: { onFinish?: () => void }) => {
    const [isSpeaking, setIsSpeaking] = useState(false);
    const [isPaused, setIsPaused] = useState(false);
    const [chunks, setChunks] = useState<string[]>([]);
    const [currentChunkIndex, setCurrentChunkIndex] = useState(0);
    const [ttsSettings, setTtsSettings] = useState<TTSSettings>({ pitch: 1.0, rate: 1.0, chunkSize: 500 });
    const [isSettingsVisible, setIsSettingsVisible] = useState(false);
    const [isControllerVisible, setIsControllerVisible] = useState(false);


    // We need to keep track of title for the notification
    const currentTitleRef = useRef('Chapter Reading');

    // We need refs to access latest state in event listeners without re-binding
    const stateRef = useRef({
        isSpeaking: false,
        isPaused: false,
        chunks: [] as string[],
        currentChunkIndex: 0
    });

    useEffect(() => {
        stateRef.current = { isSpeaking, isPaused, chunks, currentChunkIndex };
    }, [isSpeaking, isPaused, chunks, currentChunkIndex]);

    useEffect(() => {
        loadTtsSettings();

        const playSub = DeviceEventEmitter.addListener(TTS_EVENTS.PLAY, () => {
            // Logic to play: if paused, resume. 
            if (stateRef.current.isPaused) {
                speakChunk(stateRef.current.currentChunkIndex, stateRef.current.chunks);
            }
        });
        const pauseSub = DeviceEventEmitter.addListener(TTS_EVENTS.PAUSE, async () => {
            await Speech.stop();
            setIsPaused(true);
            ttsNotificationService.updateNotification(false, currentTitleRef.current, `Paused: Chunk ${stateRef.current.currentChunkIndex + 1}`);
        });
        const nextSub = DeviceEventEmitter.addListener(TTS_EVENTS.NEXT, async () => {
            await Speech.stop();
            speakChunk(stateRef.current.currentChunkIndex + 1, stateRef.current.chunks);
        });
        const prevSub = DeviceEventEmitter.addListener(TTS_EVENTS.PREVIOUS, async () => {
            await Speech.stop();
            speakChunk(Math.max(0, stateRef.current.currentChunkIndex - 1), stateRef.current.chunks);
        });
        const stopSub = DeviceEventEmitter.addListener(TTS_EVENTS.STOP, async () => {
            await stopSpeech();
        });

        // Handle Notifee Background Events (if possible) or Foreground events via dynamic require
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
                                DeviceEventEmitter.emit(TTS_EVENTS.PLAY);
                                break;
                            case 'tts_pause':
                                DeviceEventEmitter.emit(TTS_EVENTS.PAUSE);
                                break;
                            case 'tts_next':
                                DeviceEventEmitter.emit(TTS_EVENTS.NEXT);
                                break;
                            case 'tts_prev':
                                DeviceEventEmitter.emit(TTS_EVENTS.PREVIOUS);
                                break;
                            case 'tts_stop':
                                DeviceEventEmitter.emit(TTS_EVENTS.STOP);
                                break;
                        }
                    }
                });
            } catch (e) {
                // Notifee not available
            }
        }

        return () => {
            Speech.stop();
            playSub.remove();
            pauseSub.remove();
            nextSub.remove();
            prevSub.remove();
            stopSub.remove();
            if (unsubscribeNotifee) unsubscribeNotifee();
            ttsNotificationService.stopService();
        };
    }, []);

    const loadTtsSettings = async () => {
        const settings = await storageService.getTTSSettings();
        if (settings) {
            setTtsSettings(settings); // Ensure we don't set undefined if storage returns nothing, though service usually defaults
        }
    };

    const handleSettingsChange = async (newSettings: TTSSettings) => {
        setTtsSettings(newSettings);
        await storageService.saveTTSSettings(newSettings);
    };

    const stopSpeech = async () => {
        await Speech.stop();
        setIsSpeaking(false);
        setIsPaused(false);
        setIsControllerVisible(false);
        setCurrentChunkIndex(0);
        setChunks([]);
        ttsNotificationService.stopService();
    };

    const speakChunk = (index: number, chunksArray: string[]) => {
        if (index >= chunksArray.length) {
            stopSpeech();
            options?.onFinish?.();
            return;
        }

        setCurrentChunkIndex(index);
        setIsPaused(false);
        setIsSpeaking(true); // Ensure speaking is true

        // Update notification
        const msg = `Reading chunk ${index + 1} / ${chunksArray.length}`;
        if (stateRef.current.isSpeaking) {
            ttsNotificationService.updateNotification(true, currentTitleRef.current, msg);
        } else {
            ttsNotificationService.startService(currentTitleRef.current, msg);
        }

        Speech.speak(chunksArray[index], {
            pitch: ttsSettings.pitch,
            rate: ttsSettings.rate,
            voice: ttsSettings.voiceIdentifier,
            onDone: () => speakChunk(index + 1, chunksArray),
            onError: (error) => {
                console.error('Speech error:', error);
                stopSpeech();
            },
        });
    };

    const handlePlayPause = async () => {
        if (isPaused) {
            speakChunk(currentChunkIndex, chunks);
        } else {
            await Speech.stop();
            setIsPaused(true);
            ttsNotificationService.updateNotification(false, currentTitleRef.current, `Paused: Chunk ${currentChunkIndex + 1}`);
        }
    };

    const handleNextChunk = async () => {
        await Speech.stop();
        speakChunk(currentChunkIndex + 1, chunks);
    };

    const handlePreviousChunk = async () => {
        await Speech.stop();
        speakChunk(Math.max(0, currentChunkIndex - 1), chunks);
    };

    const toggleSpeech = async (newChunks: string[], title: string = 'Reading') => {
        if (isSpeaking || isControllerVisible) {
            stopSpeech();
        } else {
            if (!newChunks || newChunks.length === 0) return;

            currentTitleRef.current = title;
            setChunks(newChunks);
            setIsSpeaking(true);
            setIsControllerVisible(true);
            speakChunk(0, newChunks);
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
        setIsSettingsVisible, // Exposed setter
        setIsControllerVisible, // Exposed setter
        toggleSpeech,
        stopSpeech,
        handlePlayPause,
        handleNextChunk,
        handlePreviousChunk,
        handleSettingsChange
    };
};
