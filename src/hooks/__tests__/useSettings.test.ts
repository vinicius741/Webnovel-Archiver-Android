import { renderHook, act } from '@testing-library/react-native';
import { useSettings } from '../useSettings';
import { storageService } from '../../services/StorageService';
import { backupService } from '../../services/BackupService';
import { useTheme } from '../../theme/ThemeContext';
import { router } from 'expo-router';
import { findAndPressButton, getLastAlertCall } from '../../test-utils';

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

    it('should load settings on mount', async () => {
        const { result } = renderHook(() => useSettings());

        await act(async () => {
            await new Promise(resolve => setTimeout(resolve, 0));
        });

        expect(storageService.getSettings).toHaveBeenCalled();
        expect(result.current.concurrency).toBe('3');
        expect(result.current.delay).toBe('500');
        expect(result.current.maxChaptersPerEpub).toBe('150');
    });

    it('should return theme mode and setter', () => {
        const { result } = renderHook(() => useSettings());

        expect(result.current.themeMode).toBe('dark');
        expect(typeof result.current.setThemeMode).toBe('function');
    });

    it('should return error states', () => {
        const { result } = renderHook(() => useSettings());

        expect(result.current.concurrencyError).toBeUndefined();
        expect(result.current.delayError).toBeUndefined();
        expect(result.current.maxChaptersError).toBeUndefined();
    });

    it('should update concurrency and save on blur', async () => {
        const { result } = renderHook(() => useSettings());

        await act(async () => {
            await new Promise(resolve => setTimeout(resolve, 0));
        });

        act(() => {
            result.current.handleConcurrencyChange('5');
        });

        expect(result.current.concurrency).toBe('5');
        expect(storageService.saveSettings).not.toHaveBeenCalled();

        await act(async () => {
            await result.current.handleConcurrencyBlur();
        });

        expect(storageService.saveSettings).toHaveBeenCalledWith({
            downloadConcurrency: 5,
            downloadDelay: 500,
            maxChaptersPerEpub: 150,
        });
    });

    it('should show error for invalid concurrency and validate on blur', async () => {
        const { result } = renderHook(() => useSettings());

        await act(async () => {
            await new Promise(resolve => setTimeout(resolve, 0));
        });

        act(() => {
            result.current.handleConcurrencyChange('15');
        });

        expect(result.current.concurrency).toBe('15');
        expect(result.current.concurrencyError).toBe('Must be between 1 and 10');

        await act(async () => {
            await result.current.handleConcurrencyBlur();
        });

        expect(result.current.concurrency).toBe('10'); // Validated value
        expect(result.current.concurrencyError).toBeUndefined();
        expect(storageService.saveSettings).toHaveBeenCalledWith({
            downloadConcurrency: 10,
            downloadDelay: 500,
            maxChaptersPerEpub: 150,
        });
    });

    it('should validate and limit concurrency to minimum of 1 on blur', async () => {
        const { result } = renderHook(() => useSettings());

        await act(async () => {
            await new Promise(resolve => setTimeout(resolve, 0));
        });

        act(() => {
            result.current.handleConcurrencyChange('0');
        });

        expect(result.current.concurrencyError).toBe('Must be between 1 and 10');

        await act(async () => {
            await result.current.handleConcurrencyBlur();
        });

        expect(result.current.concurrency).toBe('1'); // Validated value
        expect(result.current.concurrencyError).toBeUndefined();
        expect(storageService.saveSettings).toHaveBeenCalledWith({
            downloadConcurrency: 1,
            downloadDelay: 500,
            maxChaptersPerEpub: 150,
        });
    });

    it('should handle invalid concurrency input gracefully on blur', async () => {
        const { result } = renderHook(() => useSettings());

        await act(async () => {
            await new Promise(resolve => setTimeout(resolve, 0));
        });

        act(() => {
            result.current.handleConcurrencyChange('invalid');
        });

        expect(result.current.concurrencyError).toBe('Must be between 1 and 10');

        await act(async () => {
            await result.current.handleConcurrencyBlur();
        });

        expect(result.current.concurrency).toBe('1'); // Default value
        expect(storageService.saveSettings).toHaveBeenCalledWith({
            downloadConcurrency: 1,
            downloadDelay: 500,
            maxChaptersPerEpub: 150,
        });
    });

    it('should update delay and save on blur', async () => {
        const { result } = renderHook(() => useSettings());

        await act(async () => {
            await new Promise(resolve => setTimeout(resolve, 0));
        });

        act(() => {
            result.current.handleDelayChange('1000');
        });

        expect(result.current.delay).toBe('1000');
        expect(storageService.saveSettings).not.toHaveBeenCalled();

        await act(async () => {
            await result.current.handleDelayBlur();
        });

        expect(storageService.saveSettings).toHaveBeenCalledWith({
            downloadConcurrency: 3,
            downloadDelay: 1000,
            maxChaptersPerEpub: 150,
        });
    });

    it('should show error for negative delay and validate on blur', async () => {
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

        expect(result.current.delay).toBe('0'); // Validated value
        expect(result.current.delayError).toBeUndefined();
        expect(storageService.saveSettings).toHaveBeenCalledWith({
            downloadConcurrency: 3,
            downloadDelay: 0,
            maxChaptersPerEpub: 150,
        });
    });

    it('should handle invalid delay input gracefully on blur', async () => {
        const { result } = renderHook(() => useSettings());

        await act(async () => {
            await new Promise(resolve => setTimeout(resolve, 0));
        });

        act(() => {
            result.current.handleDelayChange('invalid');
        });

        expect(result.current.delayError).toBe('Must be 0 or greater');

        await act(async () => {
            await result.current.handleDelayBlur();
        });

        expect(result.current.delay).toBe('0'); // Default value
        expect(storageService.saveSettings).toHaveBeenCalledWith({
            downloadConcurrency: 3,
            downloadDelay: 0,
            maxChaptersPerEpub: 150,
        });
    });

    it('should update maxChaptersPerEpub and save on blur', async () => {
        const { result } = renderHook(() => useSettings());

        await act(async () => {
            await new Promise(resolve => setTimeout(resolve, 0));
        });

        act(() => {
            result.current.handleMaxChaptersPerEpubChange('200');
        });

        expect(result.current.maxChaptersPerEpub).toBe('200');
        expect(storageService.saveSettings).not.toHaveBeenCalled();

        await act(async () => {
            await result.current.handleMaxChaptersBlur();
        });

        expect(storageService.saveSettings).toHaveBeenCalledWith({
            downloadConcurrency: 3,
            downloadDelay: 500,
            maxChaptersPerEpub: 200,
        });
    });

    it('should show error for maxChaptersPerEpub below minimum and validate on blur', async () => {
        const { result } = renderHook(() => useSettings());

        await act(async () => {
            await new Promise(resolve => setTimeout(resolve, 0));
        });

        act(() => {
            result.current.handleMaxChaptersPerEpubChange('5');
        });

        expect(result.current.maxChaptersError).toBe('Must be between 10 and 1000');

        await act(async () => {
            await result.current.handleMaxChaptersBlur();
        });

        expect(result.current.maxChaptersPerEpub).toBe('10'); // Validated value
        expect(result.current.maxChaptersError).toBeUndefined();
        expect(storageService.saveSettings).toHaveBeenCalledWith({
            downloadConcurrency: 3,
            downloadDelay: 500,
            maxChaptersPerEpub: 10,
        });
    });

    it('should show error for maxChaptersPerEpub above maximum and validate on blur', async () => {
        const { result } = renderHook(() => useSettings());

        await act(async () => {
            await new Promise(resolve => setTimeout(resolve, 0));
        });

        act(() => {
            result.current.handleMaxChaptersPerEpubChange('1500');
        });

        expect(result.current.maxChaptersError).toBe('Must be between 10 and 1000');

        await act(async () => {
            await result.current.handleMaxChaptersBlur();
        });

        expect(result.current.maxChaptersPerEpub).toBe('1000'); // Validated value
        expect(result.current.maxChaptersError).toBeUndefined();
        expect(storageService.saveSettings).toHaveBeenCalledWith({
            downloadConcurrency: 3,
            downloadDelay: 500,
            maxChaptersPerEpub: 1000,
        });
    });

    it('should handle invalid maxChaptersPerEpub input gracefully on blur', async () => {
        const { result } = renderHook(() => useSettings());

        await act(async () => {
            await new Promise(resolve => setTimeout(resolve, 0));
        });

        act(() => {
            result.current.handleMaxChaptersPerEpubChange('invalid');
        });

        expect(result.current.maxChaptersError).toBe('Must be between 10 and 1000');

        await act(async () => {
            await result.current.handleMaxChaptersBlur();
        });

        expect(result.current.maxChaptersPerEpub).toBe('150'); // Default value
        expect(storageService.saveSettings).toHaveBeenCalledWith({
            downloadConcurrency: 3,
            downloadDelay: 500,
            maxChaptersPerEpub: 150,
        });
    });

    it('should clear errors when settings are validated on blur', async () => {
        const { result } = renderHook(() => useSettings());

        await act(async () => {
            await new Promise(resolve => setTimeout(resolve, 0));
        });

        act(() => {
            result.current.handleConcurrencyChange('15');
            result.current.handleDelayChange('-100');
            result.current.handleMaxChaptersPerEpubChange('5');
        });

        expect(result.current.concurrencyError).toBeDefined();
        expect(result.current.delayError).toBeDefined();
        expect(result.current.maxChaptersError).toBeDefined();

        // Blur triggers validation and clears errors
        await act(async () => {
            await result.current.handleConcurrencyBlur();
            await result.current.handleDelayBlur();
            await result.current.handleMaxChaptersBlur();
        });

        // Errors should be cleared after validation
        expect(result.current.concurrencyError).toBeUndefined();
        expect(result.current.delayError).toBeUndefined();
        expect(result.current.maxChaptersError).toBeUndefined();
    });

    it('should handle export backup', async () => {
        const { result } = renderHook(() => useSettings());

        await act(async () => {
            await result.current.handleExportBackup();
        });

        expect(backupService.exportBackup).toHaveBeenCalled();
        expect(mockShowAlert).toHaveBeenCalledWith(
            'Export Complete',
            'Export successful'
        );
    });

    it('should handle failed export backup', async () => {
        (backupService.exportBackup as jest.Mock).mockResolvedValue({
            success: false,
            message: 'Export failed',
        });

        const { result } = renderHook(() => useSettings());

        await act(async () => {
            await result.current.handleExportBackup();
        });

        expect(mockShowAlert).toHaveBeenCalledWith(
            'Export Failed',
            'Export failed'
        );
    });

    it('should handle import backup with confirmation', async () => {
        const { result } = renderHook(() => useSettings());

        await act(async () => {
            result.current.handleImportBackup();
        });

        expect(mockShowAlert).toHaveBeenCalledWith(
            'Import Backup',
            expect.stringContaining('merge'),
            expect.arrayContaining([
                expect.objectContaining({ text: 'Cancel' }),
                expect.objectContaining({
                    text: 'Import',
                    onPress: expect.any(Function),
                }),
            ])
        );
    });

    it('should call backupService import on import confirm', async () => {
        const { result } = renderHook(() => useSettings());

        await act(async () => {
            result.current.handleImportBackup();
        });

        const importButtonPress = findAndPressButton(mockShowAlert, 'Import Backup', 'Import');
        await act(async () => {
            await importButtonPress();
        });

        expect(backupService.importBackup).toHaveBeenCalled();
    });

    it('should handle successful import backup', async () => {
        const { result } = renderHook(() => useSettings());

        await act(async () => {
            result.current.handleImportBackup();
        });

        const importButtonPress = findAndPressButton(mockShowAlert, 'Import Backup', 'Import');
        await act(async () => {
            await importButtonPress();
        });

        const successCall = getLastAlertCall(mockShowAlert);
        expect(successCall[0]).toBe('Import Complete');
        expect(successCall[1]).toBe('Import successful');
        expect(successCall[2]).toEqual(expect.arrayContaining([
            expect.objectContaining({
                text: 'OK',
                onPress: expect.any(Function),
            }),
        ]));
    });

    it('should handle failed import backup', async () => {
        (backupService.importBackup as jest.Mock).mockResolvedValue({
            success: false,
            message: 'Import failed',
        });

        const { result } = renderHook(() => useSettings());

        await act(async () => {
            result.current.handleImportBackup();
        });

        const importButtonPress = findAndPressButton(mockShowAlert, 'Import Backup', 'Import');
        await act(async () => {
            await importButtonPress();
        });

        const failCall = getLastAlertCall(mockShowAlert);
        expect(failCall[0]).toBe('Import Failed');
        expect(failCall[1]).toBe('Import failed');
        expect(failCall[2]).toBeUndefined();
    });

    it('should handle clear data with confirmation', async () => {
        const { result } = renderHook(() => useSettings());

        await act(async () => {
            result.current.clearData();
        });

        expect(mockShowAlert).toHaveBeenCalledWith(
            'Clear Data',
            expect.stringContaining('delete all novels'),
            expect.arrayContaining([
                expect.objectContaining({ text: 'Cancel' }),
                expect.objectContaining({
                    text: 'Delete',
                    style: 'destructive',
                    onPress: expect.any(Function),
                }),
            ])
        );
    });

    it('should call clearAll and navigate back on delete confirm', async () => {
        const { result } = renderHook(() => useSettings());

        await act(async () => {
            result.current.clearData();
        });

        const deleteButtonPress = findAndPressButton(mockShowAlert, 'Clear Data', 'Delete');
        await act(async () => {
            await deleteButtonPress();
        });

        const okButtonPress = findAndPressButton(mockShowAlert, 'Data Cleared', 'OK');
        okButtonPress();

        expect(storageService.clearAll).toHaveBeenCalled();
        expect(router.back).toHaveBeenCalled();
    });
});
