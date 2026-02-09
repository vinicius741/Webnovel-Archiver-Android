import { epubGenerator } from '../EpubGenerator';
import { Chapter, Story } from '../../types';
import * as fileSystem from '../storage/fileSystem';

jest.mock('jszip', () => {
    return jest.fn().mockImplementation(() => ({
        file: jest.fn(),
        folder: jest.fn(() => ({
            file: jest.fn(),
        })),
        generateAsync: jest.fn().mockResolvedValue('base64data'),
    }));
});

jest.mock('../storage/fileSystem', () => ({
    saveEpub: jest.fn().mockResolvedValue('file://test.epub'),
    readChapterFile: jest.fn().mockResolvedValue('<html><body><p>Content</p></body></html>'),
    checkFileExists: jest.fn().mockResolvedValue(true),
}));

describe('EpubGenerator', () => {
    const mockStory: Story = {
        id: '123',
        title: 'Test Story',
        author: 'Author Name',
        sourceUrl: 'http://test',
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
        (fileSystem.readChapterFile as jest.Mock).mockResolvedValue('<html><body><p>Content</p></body></html>');
    });

    it('generates a single epub and returns uri/filename metadata', async () => {
        const result = await epubGenerator.generateEpub(mockStory, mockChapters);

        expect(result).toEqual({
            uri: 'file://test.epub',
            filename: 'test_story.epub',
        });
        expect(fileSystem.saveEpub).toHaveBeenCalledWith('test_story.epub', 'base64data');
    });

    it('maps chapter ranges to original chapter numbers for single-file generation', async () => {
        const results = await epubGenerator.generateEpubs(
            mockStory,
            mockChapters,
            150,
            undefined,
            [10, 11]
        );

        expect(results).toHaveLength(1);
        expect(results[0].chapterRange).toEqual({ start: 10, end: 11 });
    });

    it('maps chapter ranges to original chapter numbers for split generation', async () => {
        const fiveChapters: Chapter[] = [
            { id: 'c1', title: 'Chapter 1', filePath: 'path/to/c1.html', url: 'http://url1' },
            { id: 'c2', title: 'Chapter 2', filePath: 'path/to/c2.html', url: 'http://url2' },
            { id: 'c3', title: 'Chapter 3', filePath: 'path/to/c3.html', url: 'http://url3' },
            { id: 'c4', title: 'Chapter 4', filePath: 'path/to/c4.html', url: 'http://url4' },
            { id: 'c5', title: 'Chapter 5', filePath: 'path/to/c5.html', url: 'http://url5' },
        ];

        const results = await epubGenerator.generateEpubs(
            mockStory,
            fiveChapters,
            2,
            undefined,
            [51, 52, 53, 54, 55]
        );

        expect(results).toHaveLength(3);
        expect(results[0].chapterRange).toEqual({ start: 51, end: 52 });
        expect(results[1].chapterRange).toEqual({ start: 53, end: 54 });
        expect(results[2].chapterRange).toEqual({ start: 55, end: 55 });
    });

    it('keeps backward-compatible range numbering without mapping', async () => {
        const results = await epubGenerator.generateEpubs(mockStory, mockChapters, 150);

        expect(results).toHaveLength(1);
        expect(results[0].chapterRange).toEqual({ start: 1, end: 2 });
    });

    it('throws when no chapters are provided', async () => {
        await expect(epubGenerator.generateEpubs(mockStory, [], 150)).rejects.toThrow(
            'No chapters available for EPUB generation.'
        );
    });
});
