import { Story, Chapter } from '../types';
import { cleanChapterTitle } from '../utils/htmlUtils';
import { EpubFileSystem } from './epub/EpubFileSystem';
import { EpubMetadataGenerator } from './epub/EpubMetadataGenerator';
import { EpubContentProcessor } from './epub/EpubContentProcessor';
import { saveEpub } from './storage/fileSystem';

export class EpubGenerator {
    async generateEpub(story: Story, chapters: Chapter[]): Promise<string> {
        // Filter chapters to only include those with available content
        const availableChapters: Chapter[] = [];
        for (const chapter of chapters) {
            if (await EpubContentProcessor.checkChapterContentAvailability(chapter)) {
                availableChapters.push(chapter);
            }
        }

        const cleanChapters = availableChapters.map(c => ({
            ...c,
            title: cleanChapterTitle(c.title)
        }));

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
        }

        const uid = EpubMetadataGenerator.generateBookId(story);
        oebps.file('content.opf', EpubMetadataGenerator.generateOpf(story, cleanChapters, uid));
        oebps.file('toc.ncx', EpubMetadataGenerator.generateNcx(story, cleanChapters, uid));

        const base64 = await fileSystem.generate();

        const filename = `${EpubMetadataGenerator.sanitizeFilename(story.title)}.epub`;
        return await saveEpub(filename, base64);
    }
}

export const epubGenerator = new EpubGenerator();
