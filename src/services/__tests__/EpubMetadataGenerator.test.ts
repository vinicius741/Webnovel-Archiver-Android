import { EpubMetadataGenerator } from '../epub/EpubMetadataGenerator';
import { Story, Chapter, DownloadStatus } from '../../types';

describe('EpubMetadataGenerator', () => {
    const mockStory: Story = {
        id: 'story-123',
        title: 'Test Story <Title>',
        author: 'Test "Author"',
        sourceUrl: 'https://example.com',
        chapters: [],
        totalChapters: 0,
        downloadedChapters: 0,
        status: DownloadStatus.Idle,
    };

    const mockChapters: Chapter[] = [
        { id: 'c1', title: 'Chapter 1: <Start>', url: 'https://example.com/c1', downloaded: false },
        { id: 'c2', title: 'Chapter 2: &Middle&', url: 'https://example.com/c2', downloaded: false },
        { id: 'c3', title: "Chapter 3: 'End'", url: 'https://example.com/c3', downloaded: false },
    ];

    describe('XML Escaping', () => {
        it('should escape XML special characters', () => {
            const unsafe = '<tag>"content"\'&more\'</tag>';
            const escaped = EpubMetadataGenerator.escapeXml(unsafe);
            
            expect(escaped).toBe('&lt;tag&gt;&quot;content&quot;&apos;&amp;more&apos;&lt;/tag&gt;');
        });

        it('should escape less than sign', () => {
            expect(EpubMetadataGenerator.escapeXml('<div>')).toBe('&lt;div&gt;');
        });

        it('should escape greater than sign', () => {
            expect(EpubMetadataGenerator.escapeXml('</div>')).toBe('&lt;/div&gt;');
        });

        it('should escape ampersand', () => {
            expect(EpubMetadataGenerator.escapeXml('Tom & Jerry')).toBe('Tom &amp; Jerry');
        });

        it('should escape single quote', () => {
            expect(EpubMetadataGenerator.escapeXml("It's")).toBe('It&apos;s');
        });

        it('should escape double quote', () => {
            expect(EpubMetadataGenerator.escapeXml('"Hello"')).toBe('&quot;Hello&quot;');
        });

        it('should handle empty string', () => {
            expect(EpubMetadataGenerator.escapeXml('')).toBe('');
        });

        it('should handle string without special characters', () => {
            expect(EpubMetadataGenerator.escapeXml('Normal Text')).toBe('Normal Text');
        });
    });

    describe('Book ID Generation', () => {
        it('should generate book ID from story ID', () => {
            const bookId = EpubMetadataGenerator.generateBookId(mockStory);
            expect(bookId).toBe('urn:webnovel:story-123');
        });

        it('should generate unique IDs for different stories', () => {
            const story2 = { ...mockStory, id: 'story-456' };
            const id1 = EpubMetadataGenerator.generateBookId(mockStory);
            const id2 = EpubMetadataGenerator.generateBookId(story2);
            
            expect(id1).not.toBe(id2);
        });
    });

    describe('Filename Sanitization', () => {
        it('should sanitize special characters', () => {
            const sanitized = EpubMetadataGenerator.sanitizeFilename('Test: Chapter 1!');
            expect(sanitized).toBe('test__chapter_1_');
        });

        it('should convert to lowercase', () => {
            const sanitized = EpubMetadataGenerator.sanitizeFilename('TITLE');
            expect(sanitized).toBe('title');
        });

        it('should replace spaces with underscores', () => {
            const sanitized = EpubMetadataGenerator.sanitizeFilename('test story');
            expect(sanitized).toBe('test_story');
        });

        it('should handle multiple special characters', () => {
            const sanitized = EpubMetadataGenerator.sanitizeFilename('Test/Chapter:One*Two?Three');
            expect(sanitized).toBe('test_chapter_one_two_three');
        });

        it('should handle empty string', () => {
            expect(EpubMetadataGenerator.sanitizeFilename('')).toBe('');
        });

        it('should handle string with only special characters', () => {
            const sanitized = EpubMetadataGenerator.sanitizeFilename('!!!***???');
            expect(sanitized).toBe('_________');
        });
    });

    describe('OPF Generation', () => {
        it('should generate valid OPF XML', () => {
            const opf = EpubMetadataGenerator.generateOpf(mockStory, mockChapters, 'test-uid');

            expect(opf).toContain('<?xml version="1.0" encoding="UTF-8"?>');
            expect(opf).toContain('<package xmlns="http://www.idpf.org/2007/opf"');
            expect(opf).toContain('unique-identifier="BookId" version="2.0">');
            expect(opf).toContain('<dc:title>Test Story &lt;Title&gt;</dc:title>');
            expect(opf).toContain('<dc:creator opf:role="aut">Test &quot;Author&quot;</dc:creator>');
            expect(opf).toContain('<dc:language>en</dc:language>');
            expect(opf).toContain('<dc:identifier id="BookId">test-uid</dc:identifier>');
        });

        it('should include all chapters in manifest', () => {
            const opf = EpubMetadataGenerator.generateOpf(mockStory, mockChapters, 'test-uid');

            expect(opf).toContain('<item id="chapter_1" href="chapter_1.xhtml" media-type="application/xhtml+xml"/>');
            expect(opf).toContain('<item id="chapter_2" href="chapter_2.xhtml" media-type="application/xhtml+xml"/>');
            expect(opf).toContain('<item id="chapter_3" href="chapter_3.xhtml" media-type="application/xhtml+xml"/>');
        });

        it('should include NCX and style items in manifest', () => {
            const opf = EpubMetadataGenerator.generateOpf(mockStory, mockChapters, 'test-uid');

            expect(opf).toContain('<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>');
            expect(opf).toContain('<item id="style" href="style.css" media-type="text/css"/>');
            expect(opf).toContain('<item id="toc" href="toc.xhtml" media-type="application/xhtml+xml"/>');
        });

        it('should include all chapters in spine', () => {
            const opf = EpubMetadataGenerator.generateOpf(mockStory, mockChapters, 'test-uid');

            expect(opf).toContain('<itemref idref="chapter_1"/>');
            expect(opf).toContain('<itemref idref="chapter_2"/>');
            expect(opf).toContain('<itemref idref="chapter_3"/>');
        });

        it('should include TOC in spine', () => {
            const opf = EpubMetadataGenerator.generateOpf(mockStory, mockChapters, 'test-uid');

            expect(opf).toContain('<itemref idref="toc"/>');
        });

        it('should include guide', () => {
            const opf = EpubMetadataGenerator.generateOpf(mockStory, mockChapters, 'test-uid');

            expect(opf).toContain('<guide>');
            expect(opf).toContain('<reference type="toc" title="Table of Contents" href="toc.xhtml"/>');
            expect(opf).toContain('</guide>');
        });

        it('should handle empty chapters list', () => {
            const opf = EpubMetadataGenerator.generateOpf(mockStory, [], 'test-uid');

            expect(opf).toContain('<manifest>');
            expect(opf).toContain('</manifest>');
            expect(opf).toContain('<spine');
        });

        it('should escape chapter titles in manifest items', () => {
            const chaptersWithSpecial: Chapter[] = [
                { id: 'c1', title: 'Chapter <1>', url: 'https://example.com/c1', downloaded: false },
            ];
            const opf = EpubMetadataGenerator.generateOpf(mockStory, chaptersWithSpecial, 'test-uid');

            expect(opf).toContain('<item id="chapter_1" href="chapter_1.xhtml" media-type="application/xhtml+xml"/>');
        });
    });

    describe('NCX Generation', () => {
        it('should generate valid NCX XML', () => {
            const ncx = EpubMetadataGenerator.generateNcx(mockStory, mockChapters, 'test-uid');

            expect(ncx).toContain('<?xml version="1.0" encoding="UTF-8"?>');
            expect(ncx).toContain('<!DOCTYPE ncx PUBLIC "-//NISO//DTD ncx 2005-1//EN"');
            expect(ncx).toContain('<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">');
            expect(ncx).toContain('</ncx>');
        });

        it('should include story title in docTitle', () => {
            const ncx = EpubMetadataGenerator.generateNcx(mockStory, mockChapters, 'test-uid');

            expect(ncx).toContain('<docTitle>');
            expect(ncx).toContain('<text>Test Story &lt;Title&gt;</text>');
            expect(ncx).toContain('</docTitle>');
        });

        it('should include book ID in meta', () => {
            const ncx = EpubMetadataGenerator.generateNcx(mockStory, mockChapters, 'test-uid');

            expect(ncx).toContain('<meta name="dtb:uid" content="test-uid"/>');
        });

        it('should include depth meta', () => {
            const ncx = EpubMetadataGenerator.generateNcx(mockStory, mockChapters, 'test-uid');

            expect(ncx).toContain('<meta name="dtb:depth" content="1"/>');
        });

        it('should include all chapters as navPoints', () => {
            const ncx = EpubMetadataGenerator.generateNcx(mockStory, mockChapters, 'test-uid');

            expect(ncx).toContain('<navPoint id="navPoint-1" playOrder="1">');
            expect(ncx).toContain('<navPoint id="navPoint-2" playOrder="2">');
            expect(ncx).toContain('<navPoint id="navPoint-3" playOrder="3">');
        });

        it('should include chapter titles in navLabels', () => {
            const ncx = EpubMetadataGenerator.generateNcx(mockStory, mockChapters, 'test-uid');

            expect(ncx).toContain('<text>Chapter 1: &lt;Start&gt;</text>');
            expect(ncx).toContain('<text>Chapter 2: &amp;Middle&amp;</text>');
            expect(ncx).toContain('<text>Chapter 3: &apos;End&apos;</text>');
        });

        it('should include chapter file paths in content', () => {
            const ncx = EpubMetadataGenerator.generateNcx(mockStory, mockChapters, 'test-uid');

            expect(ncx).toContain('<content src="chapter_1.xhtml"/>');
            expect(ncx).toContain('<content src="chapter_2.xhtml"/>');
            expect(ncx).toContain('<content src="chapter_3.xhtml"/>');
        });

        it('should handle empty chapters list', () => {
            const ncx = EpubMetadataGenerator.generateNcx(mockStory, [], 'test-uid');

            expect(ncx).toContain('<navMap>');
            expect(ncx).toContain('</navMap>');
        });
    });
});
