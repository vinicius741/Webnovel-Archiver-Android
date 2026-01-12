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

describe('useSettings', () => {
    const mockSetThemeMode = jest.fn();

    beforeEach(() => {
        jest.clearAllMocks();
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
        const Alert = require('react-native').Alert;
        jest.spyOn(Alert, 'alert');

        const { result } = renderHook(() => useSettings());

        await act(async () => {
            await result.current.handleExportBackup();
        });

        expect(backupService.exportBackup).toHaveBeenCalled();
        expect(Alert.alert).toHaveBeenCalledWith(
            'Export Complete',
            'Export successful'
        );
    });

    it('should handle failed export backup', async () => {
        const Alert = require('react-native').Alert;
        jest.spyOn(Alert, 'alert');

        (backupService.exportBackup as jest.Mock).mockResolvedValue({
            success: false,
            message: 'Export failed',
        });

        const { result } = renderHook(() => useSettings());

        await act(async () => {
            await result.current.handleExportBackup();
        });

        expect(Alert.alert).toHaveBeenCalledWith(
            'Export Failed',
            'Export failed'
        );
    });

    it('should handle import backup with confirmation', async () => {
        const Alert = require('react-native').Alert;
        const alertSpy = jest.spyOn(Alert, 'alert');

        const { result } = renderHook(() => useSettings());

        await act(async () => {
            result.current.handleImportBackup();
        });

        expect(alertSpy).toHaveBeenCalledWith(
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
        const Alert = require('react-native').Alert;
        jest.spyOn(Alert, 'alert').mockImplementation((title: any, msg: any, buttons: any) => {
            if (buttons) {
                const importButton = buttons.find((b: any) => b.text === 'Import');
                if (importButton && importButton.onPress) {
                    importButton.onPress();
                }
            }
        });

        const { result } = renderHook(() => useSettings());

        await act(async () => {
            result.current.handleImportBackup();
        });

        expect(backupService.importBackup).toHaveBeenCalled();
    });

    it('should handle successful import backup', async () => {
        const Alert = require('react-native').Alert;
        jest.spyOn(Alert, 'alert').mockImplementation((title: any, msg: any, buttons: any) => {
            if (buttons) {
                const importButton = buttons.find((b: any) => b.text === 'Import');
                if (importButton && importButton.onPress) {
                    importButton.onPress();
                }
            }
        });

        const { result } = renderHook(() => useSettings());

        await act(async () => {
            result.current.handleImportBackup();
        });

        expect(Alert.alert).toHaveBeenCalledWith(
            'Import Complete',
            'Import successful',
            expect.arrayContaining([
                expect.objectContaining({
                    text: 'OK',
                    onPress: expect.any(Function),
                }),
            ])
        );
    });

    it('should handle failed import backup', async () => {
        const Alert = require('react-native').Alert;
        jest.spyOn(Alert, 'alert').mockImplementation((title: any, msg: any, buttons: any) => {
            if (buttons) {
                const importButton = buttons.find((b: any) => b.text === 'Import');
                if (importButton && importButton.onPress) {
                    importButton.onPress();
                }
            }
        });

        (backupService.importBackup as jest.Mock).mockResolvedValue({
            success: false,
            message: 'Import failed',
        });

        const { result } = renderHook(() => useSettings());

        await act(async () => {
            result.current.handleImportBackup();
        });

        expect(Alert.alert).toHaveBeenLastCalledWith(
            'Import Failed',
            'Import failed',
            undefined
        );
    });

    it('should handle clear data with confirmation', async () => {
        const Alert = require('react-native').Alert;
        const alertSpy = jest.spyOn(Alert, 'alert');

        const { result } = renderHook(() => useSettings());

        await act(async () => {
            result.current.clearData();
        });

        expect(alertSpy).toHaveBeenCalledWith(
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
        const Alert = require('react-native').Alert;
        let nestedCallback: (() => void) | null = null;
        jest.spyOn(Alert, 'alert').mockImplementation((title: any, msg: any, buttons: any) => {
            if (buttons) {
                const deleteButton = buttons.find((b: any) => b.text === 'Delete');
                if (deleteButton && deleteButton.onPress) {
                    deleteButton.onPress();
                }
                const okButton = buttons.find((b: any) => b.text === 'OK');
                if (okButton && okButton.onPress) {
                    nestedCallback = okButton.onPress;
                }
            }
        });

        const { result } = renderHook(() => useSettings());

        await act(async () => {
            result.current.clearData();
        });

        await act(async () => {
            if (nestedCallback) nestedCallback();
        });

        expect(storageService.clearAll).toHaveBeenCalled();
        expect(router.back).toHaveBeenCalled();
    });
});
