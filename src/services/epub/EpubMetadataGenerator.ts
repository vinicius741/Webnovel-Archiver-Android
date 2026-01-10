import { Story, Chapter } from '../../types';

export class EpubMetadataGenerator {
    public static escapeXml(unsafe: string): string {
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

    public static generateBookId(story: Story): string {
        return `urn:webnovel:${story.id}`;
    }

    public static sanitizeFilename(name: string): string {
        return name.replace(/[^a-z0-9]/gi, '_').toLowerCase();
    }

    public static generateOpf(story: Story, chapters: Chapter[], uid: string): string {
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
        <dc:identifier id="BookId">${uid}</dc:identifier>
        <meta name="generator" content="Webnovel Archiver Android" />
    </metadata>
    <manifest>
        <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
        <item id="style" href="style.css" media-type="text/css"/>
        <item id="toc" href="toc.xhtml" media-type="application/xhtml+xml"/>
        ${manifestItems}
    </manifest>
    <spine toc="ncx">
        <itemref idref="toc"/>
        ${spineItems}
    </spine>
    <guide>
        <reference type="toc" title="Table of Contents" href="toc.xhtml"/>
    </guide>
</package>`;
    }

    public static generateNcx(story: Story, chapters: Chapter[], uid: string): string {
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
        <meta name="dtb:uid" content="${uid}"/>
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
}
