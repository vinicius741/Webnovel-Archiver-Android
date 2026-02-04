jest.mock('react-native', () => ({
    Platform: {
        OS: 'android',
    },
}));

jest.mock('expo-constants', () => ({
    executionEnvironment: 'bare',
}));

const mockNotifee = {
    createChannel: jest.fn().mockResolvedValue(undefined),
    displayNotification: jest.fn().mockResolvedValue(undefined),
    stopForegroundService: jest.fn().mockResolvedValue(undefined),
    cancelNotification: jest.fn().mockResolvedValue(undefined),
    requestPermission: jest.fn().mockResolvedValue(undefined),
    registerForegroundService: jest.fn(),
};

jest.mock('@notifee/react-native', () => ({
    default: mockNotifee,
    AndroidImportance: {
        LOW: 'low',
        MEDIUM: 'medium',
    },
    AndroidColor: {
        BLUE: 'blue',
    },
    AndroidCategory: {
        SERVICE: 'service',
    },
}));

jest.mock('../download/DownloadManager', () => ({
    downloadManager: {
        init: jest.fn().mockResolvedValue(undefined),
        start: jest.fn(),
    },
}));

describe('ForegroundServiceCoordinator', () => {
    beforeEach(() => {
        jest.resetModules();
        jest.clearAllMocks();
    });

    it('should display foreground notification when download state is set', async () => {
        const { setDownloadState } = require('../ForegroundServiceCoordinator');

        await setDownloadState({
            title: 'Downloading...',
            message: 'Active: 1, Pending: 2',
            current: 1,
            total: 3,
        });

        expect(mockNotifee.displayNotification).toHaveBeenCalledWith(
            expect.objectContaining({
                id: 'foreground_service',
                title: 'Downloading...',
                body: 'Active: 1, Pending: 2',
                android: expect.objectContaining({
                    asForegroundService: true,
                    progress: { max: 3, current: 1, indeterminate: false },
                }),
            })
        );
    });

    it('should stop foreground service when download state is cleared and no other work', async () => {
        const { setDownloadState, clearDownloadState } = require('../ForegroundServiceCoordinator');

        await setDownloadState({
            title: 'Downloading...',
            message: 'Active: 1, Pending: 0',
            current: 1,
            total: 1,
        });

        await clearDownloadState();

        expect(mockNotifee.stopForegroundService).toHaveBeenCalled();
        expect(mockNotifee.cancelNotification).toHaveBeenCalledWith('foreground_service');
    });

    it('should use a single notification id for download and TTS', async () => {
        const { setDownloadState, setTtsState } = require('../ForegroundServiceCoordinator');

        await setDownloadState({
            title: 'Downloading...',
            message: 'Active: 1, Pending: 0',
            current: 1,
            total: 1,
        });

        await setTtsState({
            title: 'Reading',
            body: 'Reading chunk 1 / 5',
            isPlaying: true,
        });

        const calls = mockNotifee.displayNotification.mock.calls;
        expect(calls.length).toBeGreaterThan(1);
        for (const call of calls) {
            expect(call[0].id).toBe('foreground_service');
        }
    });

    it('should register foreground service handler', async () => {
        const { registerForegroundService } = require('../ForegroundServiceCoordinator');

        await registerForegroundService();

        expect(mockNotifee.registerForegroundService).toHaveBeenCalled();
    });
});
