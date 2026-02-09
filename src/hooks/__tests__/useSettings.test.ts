import { renderHook, act } from '@testing-library/react-native';
import { router } from 'expo-router';

import { useSettings } from '../useSettings';
import { storageService } from '../../services/StorageService';
import { backupService } from '../../services/BackupService';
import { useTheme } from '../../theme/ThemeContext';

jest.mock('../../services/StorageService');
jest.mock('../../services/BackupService');
jest.mock('../../theme/ThemeContext');
jest.mock('expo-router');
jest.mock('../../context/AlertContext');

describe('useSettings', () => {
    const mockSetThemeMode = jest.fn();
    const mockShowAlert = jest.fn();

    beforeEach(() => {
        jest.clearAllMocks();
        const { useAppAlert } = require('../../context/AlertContext');
        useAppAlert.mockReturnValue({ showAlert: mockShowAlert });

        (useTheme as jest.Mock).mockReturnValue({
            themeMode: 'dark',
            setThemeMode: mockSetThemeMode,
        });

        (storageService.getSettings as jest.Mock).mockResolvedValue({
            downloadConcurrency: 3,
            downloadDelay: 500,
            maxChaptersPerEpub: 150,
        });
        (storageService.saveSettings as jest.Mock).mockResolvedValue(undefined);
        (storageService.clearAll as jest.Mock).mockResolvedValue(undefined);

        (backupService.exportBackup as jest.Mock).mockResolvedValue({
            success: true,
            message: 'Export successful',
        });
        (backupService.importBackup as jest.Mock).mockResolvedValue({
            success: true,
            message: 'Import successful',
        });

        (router.back as jest.Mock).mockImplementation(() => {});
    });

    it('loads download settings on mount', async () => {
        const { result } = renderHook(() => useSettings());

        await act(async () => {
            await new Promise(resolve => setTimeout(resolve, 0));
        });

        expect(storageService.getSettings).toHaveBeenCalled();
        expect(result.current.concurrency).toBe('3');
        expect(result.current.delay).toBe('500');
    });

    it('saves validated concurrency while preserving stored epub max setting', async () => {
        const { result } = renderHook(() => useSettings());

        await act(async () => {
            await new Promise(resolve => setTimeout(resolve, 0));
        });

        act(() => {
            result.current.handleConcurrencyChange('5');
        });

        await act(async () => {
            await result.current.handleConcurrencyBlur();
        });

        expect(storageService.saveSettings).toHaveBeenCalledWith({
            downloadConcurrency: 5,
            downloadDelay: 500,
            maxChaptersPerEpub: 150,
        });
    });

    it('validates delay and clamps negatives to 0 on blur', async () => {
        const { result } = renderHook(() => useSettings());

        await act(async () => {
            await new Promise(resolve => setTimeout(resolve, 0));
        });

        act(() => {
            result.current.handleDelayChange('-100');
        });
        expect(result.current.delayError).toBe('Must be 0 or greater');

        await act(async () => {
            await result.current.handleDelayBlur();
        });

        expect(result.current.delay).toBe('0');
        expect(storageService.saveSettings).toHaveBeenCalledWith({
            downloadConcurrency: 3,
            downloadDelay: 0,
            maxChaptersPerEpub: 150,
        });
    });

    it('clears data after delete confirmation', async () => {
        const { result } = renderHook(() => useSettings());

        act(() => {
            result.current.clearData();
        });

        const clearAlertButtons = mockShowAlert.mock.calls[0][2];
        const deleteButton = clearAlertButtons.find((button: any) => button.text === 'Delete');

        await act(async () => {
            await deleteButton.onPress();
        });

        expect(storageService.clearAll).toHaveBeenCalled();
        expect(mockShowAlert).toHaveBeenCalledWith(
            'Data Cleared',
            'All data has been deleted.',
            expect.any(Array)
        );
    });

    it('exports backup and shows success alert', async () => {
        const { result } = renderHook(() => useSettings());

        await act(async () => {
            await result.current.handleExportBackup();
        });

        expect(backupService.exportBackup).toHaveBeenCalled();
        expect(mockShowAlert).toHaveBeenCalledWith('Export Complete', 'Export successful');
    });

    it('imports backup after confirmation', async () => {
        const { result } = renderHook(() => useSettings());

        act(() => {
            result.current.handleImportBackup();
        });

        const importAlertButtons = mockShowAlert.mock.calls[0][2];
        const importButton = importAlertButtons.find((button: any) => button.text === 'Import');

        await act(async () => {
            await importButton.onPress();
        });

        expect(backupService.importBackup).toHaveBeenCalled();
        expect(mockShowAlert).toHaveBeenCalledWith(
            'Import Complete',
            'Import successful',
            expect.any(Array)
        );
    });
});
