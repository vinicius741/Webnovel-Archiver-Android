import React, { useState, useEffect } from 'react';
import { storageService } from '../services/StorageService';
import { useTheme } from '../theme/ThemeContext';
import { useAppAlert } from '../context/AlertContext';

export const useSettings = () => {
    const { showAlert } = useAppAlert();
    const { themeMode, setThemeMode } = useTheme();
    const [concurrency, setConcurrency] = useState('1');
    const [delay, setDelay] = useState('500');

    useEffect(() => {
        loadSettings();
    }, []);

    const loadSettings = async () => {
        const settings = await storageService.getSettings();
        setConcurrency(settings.downloadConcurrency.toString());
        setDelay(settings.downloadDelay.toString());
    };

    const saveSettings = async (newConcurrency: string, newDelay: string) => {
        const downloadConcurrency = parseInt(newConcurrency) || 1;
        const downloadDelay = parseInt(newDelay) || 0;

        // Validate limits if needed
        const finalConcurrency = Math.max(1, Math.min(10, downloadConcurrency));
        const finalDelay = Math.max(0, downloadDelay);

        await storageService.saveSettings({
            downloadConcurrency: finalConcurrency,
            downloadDelay: finalDelay
        });
    };

    const handleConcurrencyChange = (text: string) => {
        setConcurrency(text);
        saveSettings(text, delay);
    };

    const handleDelayChange = (text: string) => {
        setDelay(text);
        saveSettings(concurrency, text);
    };

    const clearData = () => {
        showAlert(
            'Clear Data',
            'Are you sure you want to delete all novels and settings? This action cannot be undone.',
            [
                { text: 'Cancel', style: 'cancel' },
                {
                    text: 'Delete',
                    style: 'destructive',
                    onPress: async () => {
                        await storageService.clearAll();
                        // Potentially reload or reset state if needed, but storage clear is global
                    }
                }
            ]
        );
    };

    return {
        themeMode,
        setThemeMode,
        concurrency,
        delay,
        handleConcurrencyChange,
        handleDelayChange,
        clearData,
    };
};
