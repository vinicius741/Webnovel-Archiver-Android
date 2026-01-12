import React, { useState, useEffect } from 'react';
import { router } from 'expo-router';
import { storageService } from '../services/StorageService';
import { backupService } from '../services/BackupService';
import { useTheme } from '../theme/ThemeContext';
import { useAppAlert } from '../context/AlertContext';

export const useSettings = () => {
    const { themeMode, setThemeMode } = useTheme();
    const { showAlert } = useAppAlert();
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
        handleConcurrencyChange,
        handleDelayChange,
        clearData,
        handleExportBackup,
        handleImportBackup,
    };
};
