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
}

export class EpubGenerator {
    async generateEpub(
        story: Story,
        chapters: Chapter[],
        onProgress?: (progress: EpubProgress) => void
    ): Promise<string> {
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

        const filename = `${EpubMetadataGenerator.sanitizeFilename(story.title)}.epub`;
        const uri = await saveEpub(filename, base64);

        onProgress?.({ current: 3, total: 3, percentage: 100, stage: 'finalizing' });

        return uri;
    }
}

export const epubGenerator = new EpubGenerator();
