
import { backupService } from '../BackupService';
import { storageService } from '../StorageService';
import * as DocumentPicker from 'expo-document-picker';
import { File } from 'expo-file-system';
import * as Sharing from 'expo-sharing';
import { Story, DownloadStatus } from '../../types';

// Mock dependencies
jest.mock('expo-document-picker');
jest.mock('expo-sharing');
jest.mock('../StorageService');

// Define mock factory for expo-file-system
jest.mock('expo-file-system', () => {
    return {
        File: jest.fn(),
        Paths: { cache: 'cache://' },
    };
});

describe('BackupService', () => {
    const mockStory: Story = {
        id: '1',
        title: 'Test Story',
        author: 'Author',
        sourceUrl: 'http://test',
        coverUrl: 'http://cover',
        chapters: [
            { id: 'c1', title: 'Chapter 1', url: 'http://c1', downloaded: true, filePath: 'path/to/c1' }
        ],
        status: DownloadStatus.Completed,
        totalChapters: 1,
        downloadedChapters: 1,
        dateAdded: 1000,
        lastUpdated: 2000,
        lastReadChapterId: 'c1',
        epubPath: 'path/to/epub'
    };

    // Helper to setup File mock for a test
    const setupFileMock = (instanceOverrides: any = {}) => {
        const defaultInstance = {
            exists: false,
            create: jest.fn(),
            write: jest.fn(),
            text: jest.fn().mockResolvedValue(''),
            uri: 'file://test',
            ...instanceOverrides
        };
        (File as unknown as jest.Mock).mockImplementation(() => defaultInstance);
        return defaultInstance;
    };

    beforeEach(() => {
        jest.clearAllMocks();
    });

    describe('exportBackup', () => {
        it('should return error if library is empty', async () => {
            (storageService.getLibrary as jest.Mock).mockResolvedValue([]);

            const result = await backupService.exportBackup();

            expect(result.success).toBe(false);
            expect(result.message).toBe('Your library is empty');
        });

        it('should successfully export backup', async () => {
            (storageService.getLibrary as jest.Mock).mockResolvedValue([mockStory]);
            (Sharing.isAvailableAsync as jest.Mock).mockResolvedValue(true);

            const mockFile = setupFileMock({ exists: false });

            const result = await backupService.exportBackup();

            expect(result.success).toBe(true);
            expect(storageService.getLibrary).toHaveBeenCalled();
            expect(mockFile.create).toHaveBeenCalled();
            expect(mockFile.write).toHaveBeenCalled();

            // Verify sensitive/local paths are cleared
            const writeArg = JSON.parse(mockFile.write.mock.calls[0][0]);
            expect(writeArg.library[0].epubPath).toBeUndefined();
            expect(writeArg.library[0].chapters[0].filePath).toBeUndefined();
            expect(writeArg.library[0].chapters[0].downloaded).toBe(false);

            expect(Sharing.shareAsync).toHaveBeenCalledWith('file://test', expect.anything());
        });

        it('should return error if sharing is not available', async () => {
            (storageService.getLibrary as jest.Mock).mockResolvedValue([mockStory]);
            (Sharing.isAvailableAsync as jest.Mock).mockResolvedValue(false);
            setupFileMock({ exists: false });

            const result = await backupService.exportBackup();

            expect(result.success).toBe(false);
            expect(result.message).toContain('Sharing is not available');
        });
    });

    describe('importBackup', () => {
        it('should handle user cancellation', async () => {
            (DocumentPicker.getDocumentAsync as jest.Mock).mockResolvedValue({ canceled: true });

            const result = await backupService.importBackup();

            expect(result.success).toBe(false);
            expect(result.message).toBe('No file selected');
        });

        it('should return error if file not found', async () => {
            (DocumentPicker.getDocumentAsync as jest.Mock).mockResolvedValue({
                canceled: false,
                assets: [{ uri: 'file://test.json' }]
            });
            setupFileMock({ exists: false });

            const result = await backupService.importBackup();

            expect(result.success).toBe(false);
            expect(result.message).toBe('File not found');
        });

        it('should handle invalid JSON', async () => {
            (DocumentPicker.getDocumentAsync as jest.Mock).mockResolvedValue({
                canceled: false,
                assets: [{ uri: 'file://test.json' }]
            });
            setupFileMock({ exists: true, text: jest.fn().mockResolvedValue('invalid json') });

            const result = await backupService.importBackup();

            expect(result.success).toBe(false);
            expect(result.message).toContain('not valid JSON');
        });

        it('should validate backup version and schema', async () => {
            (DocumentPicker.getDocumentAsync as jest.Mock).mockResolvedValue({
                canceled: false,
                assets: [{ uri: 'file://test.json' }]
            });

            // Missing version
            setupFileMock({ exists: true, text: jest.fn().mockResolvedValue(JSON.stringify({ library: [] })) });
            let result = await backupService.importBackup();
            expect(result.success).toBe(false);
            expect(result.message).toContain('missing version');

            // Missing library
            setupFileMock({ exists: true, text: jest.fn().mockResolvedValue(JSON.stringify({ version: 1 })) });
            result = await backupService.importBackup();
            expect(result.success).toBe(false);
            expect(result.message).toContain('missing library');
        });

        it('should successfully import and merge library', async () => {
            const backupLibrary = [
                { ...mockStory, id: '1', title: 'Updated Title' }, // Update existing
                { ...mockStory, id: '2', title: 'New Story' }      // New story
            ];

            (DocumentPicker.getDocumentAsync as jest.Mock).mockResolvedValue({
                canceled: false,
                assets: [{ uri: 'file://test.json' }]
            });

            setupFileMock({
                exists: true,
                text: jest.fn().mockResolvedValue(JSON.stringify({
                    version: 1,
                    library: backupLibrary
                }))
            });

            // Existing library has only story 1
            const existingLibrary = [mockStory];
            (storageService.getLibrary as jest.Mock).mockResolvedValue(existingLibrary);

            const result = await backupService.importBackup();

            expect(result.success).toBe(true);
            expect(result.stats).toEqual({ added: 1, updated: 1 });

            expect(storageService.saveLibrary).toHaveBeenCalled();
            const savedLibrary = (storageService.saveLibrary as jest.Mock).mock.calls[0][0];
            expect(savedLibrary.length).toBe(2);
            expect(savedLibrary.find((s: Story) => s.id === '1').title).toBe('Updated Title');
            expect(savedLibrary.find((s: Story) => s.id === '1').downloadedChapters).toBe(mockStory.downloadedChapters);
        });
    });
});
