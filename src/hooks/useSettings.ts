import React, { useState, useEffect, useCallback } from 'react';
import { router } from 'expo-router';
import { storageService } from '../services/StorageService';
import { backupService } from '../services/BackupService';
import { useTheme } from '../theme/ThemeContext';
import { useAppAlert } from '../context/AlertContext';

// Validation functions
const validateConcurrency = (value: string): { valid: string; actual: number } => {
    const num = parseInt(value) || 1;
    const clamped = Math.max(1, Math.min(10, num));
    return { valid: clamped.toString(), actual: clamped };
};

const validateDelay = (value: string): { valid: string; actual: number } => {
    const num = parseInt(value) || 0;
    const clamped = Math.max(0, num);
    return { valid: clamped.toString(), actual: clamped };
};

const validateMaxChapters = (value: string): { valid: string; actual: number } => {
    const num = parseInt(value) || 150;
    const clamped = Math.max(10, Math.min(1000, num));
    return { valid: clamped.toString(), actual: clamped };
};

export const useSettings = () => {
    const { themeMode, setThemeMode } = useTheme();
    const { showAlert } = useAppAlert();
    const [concurrency, setConcurrency] = useState('1');
    const [delay, setDelay] = useState('500');
    const [maxChaptersPerEpub, setMaxChaptersPerEpub] = useState('150');
    const [concurrencyError, setConcurrencyError] = useState<string | undefined>();
    const [delayError, setDelayError] = useState<string | undefined>();
    const [maxChaptersError, setMaxChaptersError] = useState<string | undefined>();

    useEffect(() => {
        loadSettings();
    }, []);

    const loadSettings = async () => {
        const settings = await storageService.getSettings();
        setConcurrency(settings.downloadConcurrency.toString());
        setDelay(settings.downloadDelay.toString());
        setMaxChaptersPerEpub(settings.maxChaptersPerEpub.toString());
        // Clear any errors on load
        setConcurrencyError(undefined);
        setDelayError(undefined);
        setMaxChaptersError(undefined);
    };

    const saveSettings = useCallback(async () => {
        const concurrencyResult = validateConcurrency(concurrency);
        const delayResult = validateDelay(delay);
        const maxChaptersResult = validateMaxChapters(maxChaptersPerEpub);

        // Update state with validated values
        setConcurrency(concurrencyResult.valid);
        setDelay(delayResult.valid);
        setMaxChaptersPerEpub(maxChaptersResult.valid);

        // Clear errors since we're applying validated values
        setConcurrencyError(undefined);
        setDelayError(undefined);
        setMaxChaptersError(undefined);

        await storageService.saveSettings({
            downloadConcurrency: concurrencyResult.actual,
            downloadDelay: delayResult.actual,
            maxChaptersPerEpub: maxChaptersResult.actual
        });
    }, [concurrency, delay, maxChaptersPerEpub]);

    const handleConcurrencyChange = (text: string) => {
        setConcurrency(text);
        // Validate immediately for feedback
        const num = parseInt(text);
        if (isNaN(num) || num < 1 || num > 10) {
            setConcurrencyError('Must be between 1 and 10');
        } else {
            setConcurrencyError(undefined);
        }
        // Debounced save will be triggered on blur
    };

    const handleDelayChange = (text: string) => {
        setDelay(text);
        const num = parseInt(text);
        if (isNaN(num) || num < 0) {
            setDelayError('Must be 0 or greater');
        } else {
            setDelayError(undefined);
        }
    };

    const handleMaxChaptersPerEpubChange = (text: string) => {
        setMaxChaptersPerEpub(text);
        const num = parseInt(text);
        if (isNaN(num) || num < 10 || num > 1000) {
            setMaxChaptersError('Must be between 10 and 1000');
        } else {
            setMaxChaptersError(undefined);
        }
    };

    const handleConcurrencyBlur = () => {
        saveSettings();
    };

    const handleDelayBlur = () => {
        saveSettings();
    };

    const handleMaxChaptersBlur = () => {
        saveSettings();
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
                        showAlert('Data Cleared', 'All data has been deleted.', [
                            { text: 'OK', onPress: () => router.back() }
                        ]);
                    }
                }
            ]
        );
    };

    const handleExportBackup = async () => {
        const result = await backupService.exportBackup();
        showAlert(
            result.success ? 'Export Complete' : 'Export Failed',
            result.message
        );
    };

    const handleImportBackup = async () => {
        showAlert(
            'Import Backup',
            'This will merge the backup with your existing library. Continue?',
            [
                { text: 'Cancel', style: 'cancel' },
                {
                    text: 'Import',
                    onPress: async () => {
                        const result = await backupService.importBackup();
                        showAlert(
                            result.success ? 'Import Complete' : 'Import Failed',
                            result.message,
                            result.success ? [
                                { text: 'OK', onPress: () => router.back() }
                            ] : undefined
                        );
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
        maxChaptersPerEpub,
        concurrencyError,
        delayError,
        maxChaptersError,
        handleConcurrencyChange,
        handleDelayChange,
        handleMaxChaptersPerEpubChange,
        handleConcurrencyBlur,
        handleDelayBlur,
        handleMaxChaptersBlur,
        clearData,
        handleExportBackup,
        handleImportBackup,
    };
};
