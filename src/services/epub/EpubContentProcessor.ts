import { load } from 'cheerio';
import { Story, Chapter } from '../../types';
import { readChapterFile, checkFileExists } from '../storage/fileSystem';
import { EpubMetadataGenerator } from './EpubMetadataGenerator';

export class EpubContentProcessor {
    public static generateTocHtml(story: Story, chapters: Chapter[]): string {
        const listItems = chapters.map((c, i) =>
            `<li><a href="chapter_${i + 1}.xhtml">${EpubMetadataGenerator.escapeXml(c.title)}</a></li>`
        ).join('\n        ');

        return `<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN"
  "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">
<html xmlns="http://www.w3.org/1999/xhtml">
<head>
    <title>Table of Contents</title>
    <link href="style.css" type="text/css" rel="stylesheet"/>
</head>
<body>
    <h1>Table of Contents</h1>
    <ul>
        ${listItems}
    </ul>
</body>
</html>`;
    }

    public static generateChapterHtml(chapter: Chapter, content: string): string {
        return `<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN"
  "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">
<html xmlns="http://www.w3.org/1999/xhtml">
<head>
    <title>${EpubMetadataGenerator.escapeXml(chapter.title)}</title>
    <link href="style.css" type="text/css" rel="stylesheet"/>
</head>
<body>
    <h2>${EpubMetadataGenerator.escapeXml(chapter.title)}</h2>
    <div class="content">
        ${content}
    </div>
</body>
</html>`;
    }

    public static generateCss(): string {
        return `
body { font-family: sans-serif; margin: 1em; }
h1, h2 { text-align: center; border-bottom: 1px solid #ccc; padding-bottom: 0.5em; }
p { margin-bottom: 1em; line-height: 1.6; }
ul { list-style-type: none; padding: 0; }
li { margin-bottom: 0.5em; }
a { text-decoration: none; color: #000; }
a:hover { text-decoration: underline; }
        `;
    }

    public static sanitizeContent(html: string): string {
        const $ = load(html, { xmlMode: false });
        return $('body').contents().map((_, el) => $.xml(el)).get().join('');
    }

    public static async readChapterContent(chapter: Chapter): Promise<string> {
        if (chapter.content) return chapter.content;

        if (chapter.filePath) {
            const text = await readChapterFile(chapter.filePath);
            if (text) {
                return this.sanitizeContent(text);
            }
            return `<p>[Error loading content for "${chapter.title}"]</p>`;
        }

        return `<p>[No content available]</p>`;
    }

    public static async checkChapterContentAvailability(chapter: Chapter): Promise<boolean> {
        if (chapter.content) return true;

        if (chapter.filePath) {
            return await checkFileExists(chapter.filePath);
        }

        return false;
    }
}
