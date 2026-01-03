import { removeUnwantedSentences, cleanChapterTitle, extractPlainText } from '../htmlUtils';

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
});
