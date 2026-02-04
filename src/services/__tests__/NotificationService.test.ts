
jest.mock('../ForegroundServiceCoordinator', () => ({
    setDownloadState: jest.fn().mockResolvedValue(undefined),
    clearDownloadState: jest.fn().mockResolvedValue(undefined),
    showDownloadCompletionNotification: jest.fn().mockResolvedValue(undefined),
    requestPermissions: jest.fn().mockResolvedValue(undefined),
}));

describe('NotificationService', () => {
    beforeEach(() => {
        jest.clearAllMocks();
    });

    it('should start foreground service', async () => {
        const { notificationService } = require('../NotificationService');
        const { setDownloadState } = require('../ForegroundServiceCoordinator');

        await notificationService.startForegroundService('Test Title', 'Test Body', 100, 50);

        expect(setDownloadState).toHaveBeenCalledWith({
            title: 'Test Title',
            message: 'Test Body',
            total: 100,
            current: 50,
        });
    });

    it('should update progress notification', async () => {
        const { notificationService } = require('../NotificationService');
        const { setDownloadState } = require('../ForegroundServiceCoordinator');

        await notificationService.updateProgress(75, 100, 'Downloading');

        expect(setDownloadState).toHaveBeenCalledWith({
            title: 'Downloading...',
            message: 'Downloading',
            total: 100,
            current: 75,
        });
    });

    it('should not update progress when total is zero or negative', async () => {
        const { notificationService } = require('../NotificationService');
        const { setDownloadState } = require('../ForegroundServiceCoordinator');

        await notificationService.updateProgress(75, 0, 'Test');

        expect(setDownloadState).not.toHaveBeenCalled();
    });

    it('should stop foreground service', async () => {
        const { notificationService } = require('../NotificationService');
        const { clearDownloadState } = require('../ForegroundServiceCoordinator');

        await notificationService.stopForegroundService();

        expect(clearDownloadState).toHaveBeenCalled();
    });

    it('should show completion notification', async () => {
        const { notificationService } = require('../NotificationService');
        const { showDownloadCompletionNotification } = require('../ForegroundServiceCoordinator');

        await notificationService.showCompletionNotification('Download Complete', 'All files downloaded');

        expect(showDownloadCompletionNotification).toHaveBeenCalledWith(
            'Download Complete',
            'All files downloaded'
        );
    });

    it('should request permissions', async () => {
        const { notificationService } = require('../NotificationService');
        const { requestPermissions } = require('../ForegroundServiceCoordinator');

        await notificationService.requestPermissions();

        expect(requestPermissions).toHaveBeenCalled();
    });

    it('should not throw error when coordinator is available', async () => {
        const { notificationService } = require('../NotificationService');

        expect(notificationService).toBeDefined();
    });
});
