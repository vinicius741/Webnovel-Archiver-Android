import { sanitizeTitle } from '../stringUtils';

describe('sanitizeTitle', () => {
    it('should remove trailing ellipsis', () => {
        expect(sanitizeTitle('Test Story...')).toBe('Test Story');
    });

    it('should remove trailing horizontal ellipsis character', () => {
        expect(sanitizeTitle('Test Story…')).toBe('Test Story');
    });

    it('should remove trailing vertical ellipsis character', () => {
        expect(sanitizeTitle('Test Story⋮')).toBe('Test Story');
    });

    it('should remove trailing midline ellipsis character', () => {
        expect(sanitizeTitle('Test Story⋯')).toBe('Test Story');
    });

    it('should remove multiple trailing dots', () => {
        expect(sanitizeTitle('Test Story....')).toBe('Test Story');
    });

    it('should trim leading and trailing whitespace', () => {
        expect(sanitizeTitle('  Test Story  ')).toBe('Test Story');
    });

    it('should trim and remove ellipsis', () => {
        expect(sanitizeTitle('  Test Story...  ')).toBe('Test Story');
    });

    it('should handle single trailing dot', () => {
        expect(sanitizeTitle('Test Story.')).toBe('Test Story.');
    });

    it('should handle title without trailing punctuation', () => {
        expect(sanitizeTitle('Test Story')).toBe('Test Story');
    });

    it('should handle empty string', () => {
        expect(sanitizeTitle('')).toBe('');
    });

    it('should handle only whitespace', () => {
        expect(sanitizeTitle('   ')).toBe('');
    });

    it('should handle only ellipsis', () => {
        expect(sanitizeTitle('...')).toBe('');
    });

    it('should handle only horizontal ellipsis', () => {
        expect(sanitizeTitle('…')).toBe('');
    });

    it('should preserve internal ellipsis', () => {
        expect(sanitizeTitle('Test...Story')).toBe('Test...Story');
    });

    it('should preserve internal dots', () => {
        expect(sanitizeTitle('Test.Story')).toBe('Test.Story');
    });

    it('should handle multiple spaces', () => {
        expect(sanitizeTitle('Test   Story')).toBe('Test   Story');
    });

    it('should handle title with special characters', () => {
        expect(sanitizeTitle('Test Story! @#$%')).toBe('Test Story! @#$%');
    });

    it('should handle title ending with ellipsis after whitespace', () => {
        expect(sanitizeTitle('Test Story ...')).toBe('Test Story');
    });

    it('should handle very long title with ellipsis', () => {
        const longTitle = 'This is a very long title that goes on and on and on and on...';
        expect(sanitizeTitle(longTitle)).toBe('This is a very long title that goes on and on and on and on');
    });

    it('should handle title with newline characters', () => {
        expect(sanitizeTitle('Test Story\n')).toBe('Test Story');
    });
});
