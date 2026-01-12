import { removeUnwantedSentences, cleanChapterTitle, extractPlainText, extractFormattedText } from '../htmlUtils';

describe('htmlUtils', () => {
    describe('extractPlainText', () => {
        it('should extract text from simple HTML', () => {
            const html = '<div><p>Hello</p><p>World</p></div>';
            expect(extractPlainText(html)).toBe('Hello World');
        });

        it('should remove scripts and styles', () => {
            const html = '<div><style>.test{color: red}</style><p>Text</p><script>alert(1)</script></div>';
            expect(extractPlainText(html)).toBe('Text');
        });

        it('should trim and collapse whitespace', () => {
            const html = '  <div>  <p>  Line   1  </p>  <p>   Line 2  </p>   </div>  ';
            expect(extractPlainText(html)).toBe('Line 1 Line 2');
        });
    });
    describe('removeUnwantedSentences', () => {
        it('should remove exact matching sentences', () => {
            const content = 'This is a test. Unwanted sentence. More content.';
            const removalList = ['Unwanted sentence.'];
            const expected = 'This is a test.  More content.';
            expect(removeUnwantedSentences(content, removalList)).toBe(expected);
        });

        it('should remove multiple occurrences', () => {
            const content = 'Bad. Good. Bad.';
            const removalList = ['Bad.'];
            const expected = ' Good. ';
            expect(removeUnwantedSentences(content, removalList)).toBe(expected);
        });

        it('should handle empty removal list', () => {
            const content = 'Hello world';
            expect(removeUnwantedSentences(content, [])).toBe('Hello world');
        });
    });

    describe('cleanChapterTitle', () => {
        it('should remove "time ago" suffixes', () => {
            expect(cleanChapterTitle('Chapter 1 (2 hours ago)')).toBe('Chapter 1');
            expect(cleanChapterTitle('Chapter 2 - 5 days ago')).toBe('Chapter 2');
            expect(cleanChapterTitle('Chapter 3 | an hour ago')).toBe('Chapter 3');
        });

        it('should remove absolute dates', () => {
            expect(cleanChapterTitle('Chapter 1 Nov 25, 2025')).toBe('Chapter 1');
            expect(cleanChapterTitle('Chapter 2 - 25 Nov 2025')).toBe('Chapter 2');
        });

        it('should handle clean titles', () => {
            expect(cleanChapterTitle('Normal Chapter Title')).toBe('Normal Chapter Title');
        });
    });

    describe('extractFormattedText', () => {
        it('should extract formatted text from simple HTML', () => {
            const html = '<div><p>Hello</p><p>World</p></div>';
            expect(extractFormattedText(html)).toBe('Hello\nWorld');
        });

        it('should handle nested block elements correctly', () => {
            const html = '<div><p>Para 1</p><div><p>Para 2</p></div></div>';
            expect(extractFormattedText(html)).toBe('Para 1\nPara 2');
        });

        it('should add double newlines for major block elements', () => {
            const html = '<p>Text</p><h1>Heading</h1><p>More text</p>';
            expect(extractFormattedText(html)).toBe('Text\n\nHeading\n\nMore text');
        });

        it('should handle deeply nested structures', () => {
            const html = '<div><div><div><p>Deep text</p></div></div></div>';
            expect(extractFormattedText(html)).toBe('Deep text');
        });

        it('should remove scripts and styles', () => {
            const html = '<div><style>.test{color: red}</style><p>Text</p><script>alert(1)</script></div>';
            expect(extractFormattedText(html)).toBe('Text');
        });

        it('should handle br tags as newlines', () => {
            const html = '<p>Line 1<br>Line 2</p>';
            expect(extractFormattedText(html)).toBe('Line 1\nLine 2');
        });

        it('should handle mixed nested and sibling elements', () => {
            const html = '<div><p>P1</p><div><p>P2</p><p>P3</p></div><p>P4</p></div>';
            expect(extractFormattedText(html)).toBe('P1\nP2\nP3\nP4');
        });

        it('should handle table cells with separators', () => {
            const html = '<table><tr><td>cell1</td><td>cell2</td></tr></table>';
            expect(extractFormattedText(html)).toBe('cell1 | cell2');
        });

        it('should handle table with multiple rows and cells', () => {
            const html = '<table><tr><td>A1</td><td>A2</td></tr><tr><td>B1</td><td>B2</td></tr></table>';
            expect(extractFormattedText(html)).toBe('A1 | A2\nB1 | B2');
        });

        it('should handle table with th headers', () => {
            const html = '<table><tr><th>Name</th><th>Value</th></tr><tr><td>A</td><td>1</td></tr></table>';
            expect(extractFormattedText(html)).toBe('Name | Value\nA | 1');
        });

        it('should handle empty table cells', () => {
            const html = '<table><tr><td></td><td>cell2</td></tr></table>';
            expect(extractFormattedText(html)).toBe(' | cell2');
        });
    });
});
