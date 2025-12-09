import JSZip from 'jszip';
import { load } from 'cheerio';
import { Story, Chapter } from '../types';
import { saveEpub, readChapterFile } from './storage/fileSystem';

export class EpubGenerator {
    /**
     * Generates an EPUB file for the given story and returns the local URI of the generated file.
     */
    async generateEpub(story: Story, chapters: Chapter[]): Promise<string> {
        const zip = new JSZip();

        // 1. Mimetype (must be first and uncompressed)
        zip.file('mimetype', 'application/epub+zip', { compression: 'STORE' });

        // 2. Container XML
        zip.folder('META-INF')?.file('container.xml', this.generateContainerXml());

        // 3. Content (OEBPS)
        const oebps = zip.folder('OEBPS');
        if (!oebps) throw new Error('Failed to create OEBPS folder');

        // Add CSS
        oebps.file('style.css', this.generateCss());

        // Add Chapters
        for (let i = 0; i < chapters.length; i++) {
            const chapter = chapters[i];
            const content = await this.readChapterContent(chapter);
            const xhtml = this.generateChapterHtml(chapter, content);
            oebps.file(`chapter_${i + 1}.xhtml`, xhtml);
        }

        // Add TF-IDF / Metadata / Navigation
        oebps.file('content.opf', this.generateOpf(story, chapters));
        oebps.file('toc.ncx', this.generateNcx(story, chapters));

        // 4. Generate Zip
        const base64 = await zip.generateAsync({ type: 'base64' });

        // 5. Save to file system
        const filename = `${this.sanitizeFilename(story.title)}.epub`;
        return await saveEpub(filename, base64);
    }

    private generateContainerXml(): string {
        return `<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
    <rootfiles>
        <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
    </rootfiles>
</container>`;
    }

    private generateOpf(story: Story, chapters: Chapter[]): string {
        const uid = `urn:uuid:${story.id}`; // Simple UUID simulation
        const manifestItems = chapters.map((_, i) =>
            `<item id="chapter_${i + 1}" href="chapter_${i + 1}.xhtml" media-type="application/xhtml+xml"/>`
        ).join('\n        ');

        const spineItems = chapters.map((_, i) =>
            `<itemref idref="chapter_${i + 1}"/>`
        ).join('\n        ');

        return `<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" unique-identifier="BookId" version="2.0">
    <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
        <dc:title>${this.escapeXml(story.title)}</dc:title>
        <dc:creator opf:role="aut">${this.escapeXml(story.author)}</dc:creator>
        <dc:language>en</dc:language>
        <dc:identifier id="BookId" opf:scheme="UUID">${uid}</dc:identifier>
        <meta name="generator" content="Webnovel Archiver Android" />
    </metadata>
    <manifest>
        <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
        <item id="style" href="style.css" media-type="text/css"/>
        ${manifestItems}
    </manifest>
    <spine toc="ncx">
        ${spineItems}
    </spine>
</package>`;
    }

    private generateNcx(story: Story, chapters: Chapter[]): string {
        const navPoints = chapters.map((c, i) => `
        <navPoint id="navPoint-${i + 1}" playOrder="${i + 1}">
            <navLabel>
                <text>${this.escapeXml(c.title)}</text>
            </navLabel>
            <content src="chapter_${i + 1}.xhtml"/>
        </navPoint>`).join('');

        return `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE ncx PUBLIC "-//NISO//DTD ncx 2005-1//EN"
   "http://www.daisy.org/z3986/2005/ncx-2005-1.dtd">
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
    <head>
        <meta name="dtb:uid" content="urn:uuid:${story.id}"/>
        <meta name="dtb:depth" content="1"/>
        <meta name="dtb:totalPageCount" content="0"/>
        <meta name="dtb:maxPageNumber" content="0"/>
    </head>
    <docTitle>
        <text>${this.escapeXml(story.title)}</text>
    </docTitle>
    <navMap>
        ${navPoints}
    </navMap>
</ncx>`;
    }

    private generateChapterHtml(chapter: Chapter, content: string): string {
        return `<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN"
  "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">
<html xmlns="http://www.w3.org/1999/xhtml">
<head>
    <title>${this.escapeXml(chapter.title)}</title>
    <link href="style.css" type="text/css" rel="stylesheet"/>
</head>
<body>
    <h2>${this.escapeXml(chapter.title)}</h2>
    <div class="content">
        ${content}
    </div>
</body>
</html>`;
    }

    private generateCss(): string {
        return `
body { font-family: sans-serif; margin: 1em; }
h2 { text-align: center; border-bottom: 1px solid #ccc; padding-bottom: 0.5em; }
p { margin-bottom: 1em; line-height: 1.6; }
        `;
    }

    private async readChapterContent(chapter: Chapter): Promise<string> {
        if (chapter.content) return chapter.content;

        if (chapter.filePath) {
            const text = await readChapterFile(chapter.filePath);
            if (text) return this.sanitizeContent(text);
            return `<p>[Error loading content for "${chapter.title}"]</p>`;
        }

        return `<p>[No content available]</p>`;
    }

    private sanitizeContent(html: string): string {
        // Load HTML in loose mode (HTML5)
        const $ = load(html, { xmlMode: false });
        // Retrieve the inner XML of the body, which converts void tags (br, img) to self-closing (br/, img/)
        // We wrap map/get/join to get the string representation of the nodes
        return $('body').contents().map((_, el) => $.xml(el)).get().join('');
    }

    private escapeXml(unsafe: string): string {
        return unsafe.replace(/[<>&'"]/g, (c) => {
            switch (c) {
                case '<': return '&lt;';
                case '>': return '&gt;';
                case '&': return '&amp;';
                case '\'': return '&apos;';
                case '"': return '&quot;';
                default: return c;
            }
        });
    }

    private sanitizeFilename(name: string): string {
        return name.replace(/[^a-z0-9]/gi, '_').toLowerCase();
    }
}

export const epubGenerator = new EpubGenerator();
