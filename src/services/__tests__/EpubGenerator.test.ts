import JSZip from 'jszip';
import { epubGenerator } from '../EpubGenerator';
import { Story, Chapter } from '../../types';
import * as fileSystem from '../storage/fileSystem';
import { storageService } from '../StorageService';

// Mock JSZip
jest.mock('jszip', () => {
    return jest.fn().mockImplementation(() => ({
        file: jest.fn(),
        folder: jest.fn(() => ({
            file: jest.fn(),
        })),
        generateAsync: jest.fn().mockResolvedValue('base64data'),
    }));
});

// Mock dependencies
jest.mock('../storage/fileSystem', () => ({
    saveEpub: jest.fn().mockResolvedValue('file://test.epub'),
    readChapterFile: jest.fn(),
    checkFileExists: jest.fn(),
}));

jest.mock('../StorageService', () => ({
    storageService: {
        getSentenceRemovalList: jest.fn().mockResolvedValue(['bad sentence']),
    },
}));

describe('EpubGenerator', () => {
    const mockStory: Story = {
        id: '123',
        title: 'Test Story',
        author: 'Author Name',
        sourceUrl: 'http://test',
        coverUrl: 'http://cover',
        chapters: [],
        status: 'idle',
        totalChapters: 0,
        downloadedChapters: 0,
    };

    const mockChapters: Chapter[] = [
        { id: 'c1', title: 'Chapter 1', filePath: 'path/to/c1.html', url: 'http://url1' },
        { id: 'c2', title: 'Chapter 2', filePath: 'path/to/c2.html', url: 'http://url2' },
    ];

    beforeEach(() => {
        jest.clearAllMocks();
        (fileSystem.checkFileExists as jest.Mock).mockResolvedValue(true);
    });

    it('should generate epub and save it', async () => {
        (fileSystem.readChapterFile as jest.Mock).mockResolvedValue('<p>Chapter content. bad sentence.</p>');

        const uri = await epubGenerator.generateEpub(mockStory, mockChapters);

        expect(uri).toBe('file://test.epub');
        expect(fileSystem.saveEpub).toHaveBeenCalledWith(
            expect.stringContaining('.epub'),
            'base64data'
        );
    });

    it('should clean chapter titles', async () => {
        // Access private method by casting or using the side effect in generateEpub
        // Since we can't easily spy on private methods, we can verify the output content if we mock JSZip more deeply,
        // or we can test the `cleanChapterTitle` logic if we export it or use a public method that relies on it.
        // However, checking the mock calls to `zip.folder('OEBPS').file('toc.xhtml', ...)` would show the titles.

        // Let's refine the JSZip mock to capture calls
        const mockFolder = {
            file: jest.fn(),
        };
        const mockZip = {
            file: jest.fn(),
            folder: jest.fn().mockReturnValue(mockFolder),
            generateAsync: jest.fn().mockResolvedValue('base64data'),
        };
        (JSZip as unknown as jest.Mock).mockImplementation(() => mockZip);

        const dirtyChapters: Chapter[] = [
            { id: 'c1', title: 'Chapter 1 (2 hours ago)', filePath: 'c1', url: 'http://url1' },
            { id: 'c2', title: 'Chapter 2 - Nov 25, 2025', filePath: 'c2', url: 'http://url2' },
        ];

        (fileSystem.readChapterFile as jest.Mock).mockResolvedValue('<p>content</p>');

        await epubGenerator.generateEpub(mockStory, dirtyChapters);

        // check toc.xhtml content passed to file()
        // It's the 2nd call to folder('OEBPS').file(...) usually, or we can look at arguments.
        // The generator calls:
        // 1. style.css
        // 2. toc.xhtml
        // 3. chapter files...

        const tocCall = mockFolder.file.mock.calls.find(call => call[0] === 'toc.xhtml');
        expect(tocCall).toBeDefined();
        const tocContent = tocCall[1];

        expect(tocContent).toContain('Chapter 1');
        expect(tocContent).not.toContain('(2 hours ago)');
        expect(tocContent).toContain('Chapter 2');
        expect(tocContent).not.toContain('Nov 25, 2025');
    });

    it('should remove unwanted sentences', async () => {
        (fileSystem.readChapterFile as jest.Mock).mockResolvedValue('<html><body><p>This is a good sentence. Another good one.</p></body></html>');

        const mockFolder = {
            file: jest.fn(),
        };
        const mockZip = {
            file: jest.fn(),
            folder: jest.fn().mockReturnValue(mockFolder),
            generateAsync: jest.fn().mockResolvedValue('base64data'),
        };
        (JSZip as unknown as jest.Mock).mockImplementation(() => mockZip);

        await epubGenerator.generateEpub(mockStory, [mockChapters[0]]);

        const chapterCall = mockFolder.file.mock.calls.find(call => call[0] === 'chapter_1.xhtml');
        expect(chapterCall).toBeDefined();
        const content = chapterCall[1];

        expect(content).toContain('This is a good sentence.');
        expect(content).toContain('Another good one.');
    });

    it('should handle missing chapter content gracefully', async () => {
        (fileSystem.readChapterFile as jest.Mock).mockResolvedValue('');
        // or null/undefined if readChapterFile can return that (it returns empty string on catch)

        await epubGenerator.generateEpub(mockStory, [mockChapters[0]]);
        // Should not throw
        expect(fileSystem.saveEpub).toHaveBeenCalled();
    });

    it('should skip chapters that do not have content or file', async () => {
        const partialChapters: Chapter[] = [
            { id: 'c1', title: 'Chapter 1', filePath: 'path/to/c1.html', url: 'http://url1', content: 'Some content' },
            { id: 'c2', title: 'Chapter 2', filePath: 'path/to/c2.html', url: 'http://url2' }, // Missing content/file
        ];

        // Mock checkFileExists to return false for c2
        (fileSystem.checkFileExists as jest.Mock).mockResolvedValue(false);
        (fileSystem.readChapterFile as jest.Mock).mockResolvedValue('Some content');

        const mockFolder = {
            file: jest.fn(),
        };
        const mockZip = {
            file: jest.fn(),
            folder: jest.fn().mockReturnValue(mockFolder),
            generateAsync: jest.fn().mockResolvedValue('base64data'),
        };
        (JSZip as unknown as jest.Mock).mockImplementation(() => mockZip);

        await epubGenerator.generateEpub(mockStory, partialChapters);

        // Check TOC only has Chapter 1
        const tocCall = mockFolder.file.mock.calls.find(call => call[0] === 'toc.xhtml');
        expect(tocCall).toBeDefined();
        const tocContent = tocCall[1];

        expect(tocContent).toContain('Chapter 1');
        expect(tocContent).not.toContain('Chapter 2');

        // Check OEBPS files
        // Should have chapter_1.xhtml
        expect(mockFolder.file).toHaveBeenCalledWith('chapter_1.xhtml', expect.any(String));
        // Should NOT have chapter_2.xhtml
        expect(mockFolder.file).not.toHaveBeenCalledWith('chapter_2.xhtml', expect.any(String));
    });

    it('should generate single epub when chapters <= maxChaptersPerEpub', async () => {
        const mockFolder = {
            file: jest.fn(),
        };
        const mockZip = {
            file: jest.fn(),
            folder: jest.fn().mockReturnValue(mockFolder),
            generateAsync: jest.fn().mockResolvedValue('base64data'),
        };
        (JSZip as unknown as jest.Mock).mockImplementation(() => mockZip);
        (fileSystem.readChapterFile as jest.Mock).mockResolvedValue('<p>content</p>');

        const results = await epubGenerator.generateEpubs(mockStory, mockChapters, 150);

        expect(results).toHaveLength(1);
        expect(results[0].filename).toBe('test_story.epub');
        expect(results[0].chapterRange).toEqual({ start: 1, end: 2 });
        expect(fileSystem.saveEpub).toHaveBeenCalledTimes(1);
    });

    it('should generate multiple epubs when chapters > maxChaptersPerEpub', async () => {
        const mockFolder = {
            file: jest.fn(),
        };
        const mockZip = {
            file: jest.fn(),
            folder: jest.fn().mockReturnValue(mockFolder),
            generateAsync: jest.fn().mockResolvedValue('base64data'),
        };
        (JSZip as unknown as jest.Mock).mockImplementation(() => mockZip);
        (fileSystem.readChapterFile as jest.Mock).mockResolvedValue('<p>content</p>');

        // Create 5 chapters, max 2 per epub = 3 files
        const fiveChapters: Chapter[] = [
            { id: 'c1', title: 'Chapter 1', filePath: 'path/to/c1.html', url: 'http://url1' },
            { id: 'c2', title: 'Chapter 2', filePath: 'path/to/c2.html', url: 'http://url2' },
            { id: 'c3', title: 'Chapter 3', filePath: 'path/to/c3.html', url: 'http://url3' },
            { id: 'c4', title: 'Chapter 4', filePath: 'path/to/c4.html', url: 'http://url4' },
            { id: 'c5', title: 'Chapter 5', filePath: 'path/to/c5.html', url: 'http://url5' },
        ];

        const results = await epubGenerator.generateEpubs(mockStory, fiveChapters, 2);

        expect(results).toHaveLength(3);
        expect(results[0].filename).toBe('test_story_Vol1.epub');
        expect(results[0].chapterRange).toEqual({ start: 1, end: 2 });
        expect(results[1].filename).toBe('test_story_Vol2.epub');
        expect(results[1].chapterRange).toEqual({ start: 3, end: 4 });
        expect(results[2].filename).toBe('test_story_Vol3.epub');
        expect(results[2].chapterRange).toEqual({ start: 5, end: 5 });
        expect(fileSystem.saveEpub).toHaveBeenCalledTimes(3);
    });

    it('should report progress with currentFile and totalFiles when splitting', async () => {
        const mockFolder = {
            file: jest.fn(),
        };
        const mockZip = {
            file: jest.fn(),
            folder: jest.fn().mockReturnValue(mockFolder),
            generateAsync: jest.fn().mockResolvedValue('base64data'),
        };
        (JSZip as unknown as jest.Mock).mockImplementation(() => mockZip);
        (fileSystem.readChapterFile as jest.Mock).mockResolvedValue('<p>content</p>');

        const threeChapters: Chapter[] = [
            { id: 'c1', title: 'Chapter 1', filePath: 'path/to/c1.html', url: 'http://url1' },
            { id: 'c2', title: 'Chapter 2', filePath: 'path/to/c2.html', url: 'http://url2' },
            { id: 'c3', title: 'Chapter 3', filePath: 'path/to/c3.html', url: 'http://url3' },
        ];

        const progressCalls: any[] = [];
        await epubGenerator.generateEpubs(mockStory, threeChapters, 2, (progress) => {
            progressCalls.push(progress);
        });

        // Check that we have progress with file information
        const fileProgressCalls = progressCalls.filter(p => p.currentFile !== undefined);
        expect(fileProgressCalls.length).toBeGreaterThan(0);
        expect(fileProgressCalls[0].currentFile).toBeDefined();
        expect(fileProgressCalls[0].totalFiles).toBe(2);
    });
});
