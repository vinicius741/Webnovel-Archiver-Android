import { useState, useEffect, useCallback } from 'react';
import { router } from 'expo-router';
import { storageService } from '../services/StorageService';
import { backupService } from '../services/BackupService';
import { useTheme } from '../theme/ThemeContext';
import { useAppAlert } from '../context/AlertContext';

// Validation boundary constants
const CONCURRENCY_MIN = 1;
const CONCURRENCY_MAX = 10;
const DELAY_MIN = 0;

// Validation functions
const validateConcurrency = (value: string): { valid: string; actual: number } => {
    const num = parseInt(value) || CONCURRENCY_MIN;
    const clamped = Math.max(CONCURRENCY_MIN, Math.min(CONCURRENCY_MAX, num));
    return { valid: clamped.toString(), actual: clamped };
};

const validateDelay = (value: string): { valid: string; actual: number } => {
    const num = parseInt(value) || DELAY_MIN;
    const clamped = Math.max(DELAY_MIN, num);
    return { valid: clamped.toString(), actual: clamped };
};

export const useSettings = () => {
    const { themeMode, setThemeMode } = useTheme();
    const { showAlert } = useAppAlert();
    const [concurrency, setConcurrency] = useState('1');
    const [delay, setDelay] = useState('500');
    const [persistedMaxChaptersPerEpub, setPersistedMaxChaptersPerEpub] = useState(150);
    const [concurrencyError, setConcurrencyError] = useState<string | undefined>();
    const [delayError, setDelayError] = useState<string | undefined>();

    useEffect(() => {
        loadSettings();
    }, []);

    const loadSettings = async () => {
        const settings = await storageService.getSettings();
        setConcurrency(settings.downloadConcurrency.toString());
        setDelay(settings.downloadDelay.toString());
        setPersistedMaxChaptersPerEpub(settings.maxChaptersPerEpub);
        // Clear any errors on load
        setConcurrencyError(undefined);
        setDelayError(undefined);
    };

    const saveSettings = useCallback(async () => {
        const concurrencyResult = validateConcurrency(concurrency);
        const delayResult = validateDelay(delay);

        // Update state with validated values
        setConcurrency(concurrencyResult.valid);
        setDelay(delayResult.valid);

        // Clear errors since we're applying validated values
        setConcurrencyError(undefined);
        setDelayError(undefined);

        await storageService.saveSettings({
            downloadConcurrency: concurrencyResult.actual,
            downloadDelay: delayResult.actual,
            maxChaptersPerEpub: persistedMaxChaptersPerEpub
        });
    }, [concurrency, delay, persistedMaxChaptersPerEpub]);

    const handleConcurrencyChange = (text: string) => {
        setConcurrency(text);
        // Validate immediately for feedback
        const num = parseInt(text);
        if (isNaN(num) || num < CONCURRENCY_MIN || num > CONCURRENCY_MAX) {
            setConcurrencyError(`Must be between ${CONCURRENCY_MIN} and ${CONCURRENCY_MAX}`);
        } else {
            setConcurrencyError(undefined);
        }
        // Debounced save will be triggered on blur
    };

    const handleDelayChange = (text: string) => {
        setDelay(text);
        const num = parseInt(text);
        if (isNaN(num) || num < DELAY_MIN) {
            setDelayError(`Must be ${DELAY_MIN} or greater`);
        } else {
            setDelayError(undefined);
        }
    };

    const handleConcurrencyBlur = () => {
        saveSettings();
    };

    const handleDelayBlur = () => {
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
        concurrencyError,
        delayError,
        handleConcurrencyChange,
        handleDelayChange,
        handleConcurrencyBlur,
        handleDelayBlur,
        clearData,
        handleExportBackup,
        handleImportBackup,
    };
};
