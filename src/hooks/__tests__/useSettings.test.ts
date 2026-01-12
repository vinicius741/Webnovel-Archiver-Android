import { renderHook, act } from '@testing-library/react-native';
import { useSettings } from '../useSettings';
import { storageService } from '../../services/StorageService';
import { backupService } from '../../services/BackupService';
import { useTheme } from '../../theme/ThemeContext';
import { router } from 'expo-router';

jest.mock('../../services/StorageService');
jest.mock('../../services/BackupService');
jest.mock('../../theme/ThemeContext');
jest.mock('expo-router');
jest.mock('../../context/AlertContext');

// Helper function to find and press a button in an alert dialog
const findAndPressButton = (mockFn: jest.Mock, alertTitle: string, buttonText: string) => {
    const alertCall = mockFn.mock.calls.find(call => call[0] === alertTitle);
    if (!alertCall) {
        throw new Error(`No alert found with title "${alertTitle}"`);
    }
    const button = alertCall[2].find((b: any) => b.text === buttonText);
    if (!button) {
        throw new Error(`No button found with text "${buttonText}" in alert "${alertTitle}"`);
    }
    return button.onPress;
};

// Helper function to get the last alert call
const getLastAlertCall = (mockFn: jest.Mock) => mockFn.mock.calls.slice(-1)[0];

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
    });

    it('should return theme mode and setter', () => {
        const { result } = renderHook(() => useSettings());

        expect(result.current.themeMode).toBe('dark');
        expect(typeof result.current.setThemeMode).toBe('function');
    });

    it('should update concurrency and save settings', async () => {
        const { result } = renderHook(() => useSettings());

        await act(async () => {
            await new Promise(resolve => setTimeout(resolve, 0));
        });

        await act(async () => {
            await result.current.handleConcurrencyChange('5');
        });

        expect(result.current.concurrency).toBe('5');
        expect(storageService.saveSettings).toHaveBeenCalledWith({
            downloadConcurrency: 5,
            downloadDelay: 500,
        });
    });

    it('should validate and limit concurrency to minimum of 1', async () => {
        const { result } = renderHook(() => useSettings());

        await act(async () => {
            await new Promise(resolve => setTimeout(resolve, 0));
        });

        await act(async () => {
            await result.current.handleConcurrencyChange('0');
        });

        expect(storageService.saveSettings).toHaveBeenCalledWith({
            downloadConcurrency: 1,
            downloadDelay: 500,
        });
    });

    it('should validate and limit concurrency to maximum of 10', async () => {
        const { result } = renderHook(() => useSettings());

        await act(async () => {
            await new Promise(resolve => setTimeout(resolve, 0));
        });

        await act(async () => {
            await result.current.handleConcurrencyChange('15');
        });

        expect(storageService.saveSettings).toHaveBeenCalledWith({
            downloadConcurrency: 10,
            downloadDelay: 500,
        });
    });

    it('should handle invalid concurrency input gracefully', async () => {
        const { result } = renderHook(() => useSettings());

        await act(async () => {
            await new Promise(resolve => setTimeout(resolve, 0));
        });

        await act(async () => {
            await result.current.handleConcurrencyChange('invalid');
        });

        expect(storageService.saveSettings).toHaveBeenCalledWith({
            downloadConcurrency: 1,
            downloadDelay: 500,
        });
    });

    it('should update delay and save settings', async () => {
        const { result } = renderHook(() => useSettings());

        await act(async () => {
            await new Promise(resolve => setTimeout(resolve, 0));
        });

        await act(async () => {
            await result.current.handleDelayChange('1000');
        });

        expect(result.current.delay).toBe('1000');
        expect(storageService.saveSettings).toHaveBeenCalledWith({
            downloadConcurrency: 3,
            downloadDelay: 1000,
        });
    });

    it('should validate and limit delay to minimum of 0', async () => {
        const { result } = renderHook(() => useSettings());

        await act(async () => {
            await new Promise(resolve => setTimeout(resolve, 0));
        });

        await act(async () => {
            await result.current.handleDelayChange('-100');
        });

        expect(storageService.saveSettings).toHaveBeenCalledWith({
            downloadConcurrency: 3,
            downloadDelay: 0,
        });
    });

    it('should handle invalid delay input gracefully', async () => {
        const { result } = renderHook(() => useSettings());

        await act(async () => {
            await new Promise(resolve => setTimeout(resolve, 0));
        });

        await act(async () => {
            await result.current.handleDelayChange('invalid');
        });

        expect(storageService.saveSettings).toHaveBeenCalledWith({
            downloadConcurrency: 3,
            downloadDelay: 0,
        });
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
            importButtonPress();
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
            importButtonPress();
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
            importButtonPress();
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
            deleteButtonPress();
        });

        const okButtonPress = findAndPressButton(mockShowAlert, 'Data Cleared', 'OK');
        okButtonPress();

        expect(storageService.clearAll).toHaveBeenCalled();
        expect(router.back).toHaveBeenCalled();
    });
});
