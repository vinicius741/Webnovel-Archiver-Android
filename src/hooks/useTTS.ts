import { useState, useEffect } from 'react';
import * as Speech from 'expo-speech';
import { storageService, TTSSettings } from '../services/StorageService';
import { extractPlainText } from '../utils/htmlUtils';

export const useTTS = () => {
    const [isSpeaking, setIsSpeaking] = useState(false);
    const [isPaused, setIsPaused] = useState(false);
    const [chunks, setChunks] = useState<string[]>([]);
    const [currentChunkIndex, setCurrentChunkIndex] = useState(0);
    const [ttsSettings, setTtsSettings] = useState<TTSSettings>({ pitch: 1.0, rate: 1.0, chunkSize: 3500 });
    const [isSettingsVisible, setIsSettingsVisible] = useState(false);
    const [isControllerVisible, setIsControllerVisible] = useState(false);

    useEffect(() => {
        loadTtsSettings();
        return () => {
            Speech.stop();
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
    };

    const speakChunk = (index: number, chunksArray: string[]) => {
        if (index >= chunksArray.length) {
            stopSpeech();
            return;
        }

        setCurrentChunkIndex(index);
        setIsPaused(false);

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

    const toggleSpeech = async (htmlContent: string) => {
        if (isSpeaking || isControllerVisible) {
            stopSpeech();
        } else {
            const plainText = extractPlainText(htmlContent);
            if (!plainText) return;

            // Split text into chunks
            const newChunks: string[] = [];
            let currentText = plainText;
            const chunkSize = ttsSettings.chunkSize || 3500;

            while (currentText.length > 0) {
                if (currentText.length <= chunkSize) {
                    newChunks.push(currentText);
                    break;
                }

                // Find a good splitting point (period followed by space)
                let splitIndex = currentText.lastIndexOf('. ', chunkSize);
                if (splitIndex === -1) splitIndex = currentText.lastIndexOf(' ', chunkSize);
                if (splitIndex === -1) splitIndex = chunkSize;

                newChunks.push(currentText.substring(0, splitIndex + 1).trim());
                currentText = currentText.substring(splitIndex + 1).trim();
            }

            if (newChunks.length === 0) return;

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
