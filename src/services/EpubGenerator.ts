import { Story, Chapter } from '../types';
import { cleanChapterTitle } from '../utils/htmlUtils';
import { EpubFileSystem } from './epub/EpubFileSystem';
import { EpubMetadataGenerator } from './epub/EpubMetadataGenerator';
import { EpubContentProcessor } from './epub/EpubContentProcessor';
import { saveEpub } from './storage/fileSystem';

export interface EpubProgress {
    current: number;
    total: number;
    percentage: number;
    stage: 'filtering' | 'processing' | 'finalizing';
    currentFile?: number; // Current file being generated when splitting
    totalFiles?: number; // Total number of files when splitting
}

export interface EpubResult {
    uri: string;
    filename: string;
    chapterRange: { start: number; end: number };
}

interface GenerateEpubResult {
    uri: string;
    filename: string;
}

export class EpubGenerator {
    /**
     * Generates a single EPUB file for a story.
     * @returns { uri: string, filename: string } - The file URI and generated filename
     */
    async generateEpub(
        story: Story,
        chapters: Chapter[],
        onProgress?: (progress: EpubProgress) => void,
        fileNumber?: number,
        totalFiles?: number
    ): Promise<GenerateEpubResult> {
        onProgress?.({ current: 0, total: chapters.length, percentage: 0, stage: 'filtering' });

        const availableChapters: Chapter[] = [];
        let lastReportTime = 0;
        const reportInterval = 100; // ms

        for (let i = 0; i < chapters.length; i++) {
            const chapter = chapters[i];
            if (await EpubContentProcessor.checkChapterContentAvailability(chapter)) {
                availableChapters.push(chapter);
            }

            const now = Date.now();
            if (now - lastReportTime > reportInterval || i === chapters.length - 1) {
                onProgress?.({
                    current: i + 1,
                    total: chapters.length,
                    percentage: Math.round(((i + 1) / chapters.length) * 20),
                    stage: 'filtering'
                });
                lastReportTime = now;
            }
        }

        const cleanChapters = availableChapters.map(c => ({
            ...c,
            title: cleanChapterTitle(c.title)
        }));

        onProgress?.({ current: 0, total: cleanChapters.length, percentage: 20, stage: 'processing' });

        const fileSystem = EpubFileSystem.create();

        fileSystem.createMimetype();
        fileSystem.createMetaInf();

        const oebps = fileSystem.createOEBPSFolder();
        if (!oebps) throw new Error('Failed to create OEBPS folder');

        oebps.file('style.css', EpubContentProcessor.generateCss());
        oebps.file('toc.xhtml', EpubContentProcessor.generateTocHtml(story, cleanChapters));

        for (let i = 0; i < cleanChapters.length; i++) {
            const chapter = cleanChapters[i];
            const content = await EpubContentProcessor.readChapterContent(chapter);
            const xhtml = EpubContentProcessor.generateChapterHtml(chapter, content);
            oebps.file(`chapter_${i + 1}.xhtml`, xhtml);
            
            const now = Date.now();
            if (now - lastReportTime > reportInterval || i === cleanChapters.length - 1) {
                onProgress?.({
                    current: i + 1,
                    total: cleanChapters.length,
                    percentage: Math.round(20 + ((i + 1) / cleanChapters.length) * 70),
                    stage: 'processing'
                });
                lastReportTime = now;
            }
        }

        onProgress?.({ current: 0, total: 3, percentage: 90, stage: 'finalizing' });

        const uid = EpubMetadataGenerator.generateBookId(story);
        oebps.file('content.opf', EpubMetadataGenerator.generateOpf(story, cleanChapters, uid));
        oebps.file('toc.ncx', EpubMetadataGenerator.generateNcx(story, cleanChapters, uid));

        onProgress?.({ current: 1, total: 3, percentage: 93, stage: 'finalizing' });

        const base64 = await fileSystem.generate();

        onProgress?.({ current: 2, total: 3, percentage: 96, stage: 'finalizing' });

        const baseFilename = EpubMetadataGenerator.sanitizeFilename(story.title);
        const filename = fileNumber
            ? `${baseFilename}_Vol${fileNumber}.epub`
            : `${baseFilename}.epub`;
        const uri = await saveEpub(filename, base64);

        onProgress?.({ current: 3, total: 3, percentage: 100, stage: 'finalizing' });

        return { uri, filename };
    }

    /**
     * Generates one or more EPUB files for a story, splitting if necessary.
     * Returns an array of EpubResult objects containing URI, filename, and chapter range.
     */
    async generateEpubs(
        story: Story,
        chapters: Chapter[],
        maxChaptersPerEpub: number,
        onProgress?: (progress: EpubProgress) => void
    ): Promise<EpubResult[]> {
        if (chapters.length <= maxChaptersPerEpub) {
            // Single EPUB is sufficient
            const result = await this.generateEpub(story, chapters, onProgress);
            return [{
                uri: result.uri,
                filename: result.filename,
                chapterRange: { start: 1, end: chapters.length }
            }];
        }

        // Need to split into multiple EPUBs
        const results: EpubResult[] = [];
        const totalFiles = Math.ceil(chapters.length / maxChaptersPerEpub);
        let globalChapterIndex = 0; // For overall progress tracking
        const totalChapters = chapters.length;

        for (let fileIndex = 0; fileIndex < totalFiles; fileIndex++) {
            const startIndex = fileIndex * maxChaptersPerEpub;
            const endIndex = Math.min(startIndex + maxChaptersPerEpub, chapters.length);
            const fileChapters = chapters.slice(startIndex, endIndex);

            // Create a modified story for this volume
            // Add "Vol N" to all volumes for consistency
            const volumeStory: Story = {
                ...story,
                title: `${story.title} (Vol ${fileIndex + 1})`
            };

            // Wrap the progress callback to add file information and adjust percentage
            const wrappedProgress = (progress: EpubProgress) => {
                const basePercentage = (fileIndex / totalFiles) * 100;
                const fileProgressWeight = (progress.current / progress.total) * (100 / totalFiles);
                const adjustedPercentage = Math.min(100, basePercentage + fileProgressWeight);

                onProgress?.({
                    ...progress,
                    percentage: Math.round(adjustedPercentage),
                    currentFile: fileIndex + 1,
                    totalFiles,
                    current: globalChapterIndex + progress.current,
                    total: totalChapters
                });

                // Update global chapter index as chapters are processed
                if (progress.stage === 'processing' && progress.current === progress.total) {
                    globalChapterIndex = startIndex + fileChapters.length;
                }
            };

            const { uri, filename } = await this.generateEpub(volumeStory, fileChapters, wrappedProgress, fileIndex + 1, totalFiles);

            results.push({
                uri,
                filename,
                chapterRange: { start: startIndex + 1, end: endIndex }
            });
        }

        return results;
    }
}

export const epubGenerator = new EpubGenerator();
