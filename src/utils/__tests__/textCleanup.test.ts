import { RegexCleanupRule } from '../../types';
import {
    applyDownloadCleanup,
    applyTtsCleanupLines,
    removeUnwantedSentences,
    validateRegexCleanupRule,
} from '../textCleanup';

describe('textCleanup', () => {
    describe('validateRegexCleanupRule', () => {
        it('should validate a correct regex rule', () => {
            const result = validateRegexCleanupRule({
                name: 'Remove separators',
                pattern: '(?:[-=]){5,}',
                flags: 'gi',
            });

            expect(result.valid).toBe(true);
            expect(result.normalizedFlags).toBe('gi');
        });

        it('should reject invalid regex patterns', () => {
            const result = validateRegexCleanupRule({
                name: 'Bad pattern',
                pattern: '[',
                flags: 'i',
            });

            expect(result.valid).toBe(false);
            expect(result.error).toContain('Invalid regex');
        });

        it('should reject unsupported regex flags', () => {
            const result = validateRegexCleanupRule({
                name: 'Bad flag',
                pattern: 'abc',
                flags: 'x',
            });

            expect(result.valid).toBe(false);
            expect(result.error).toContain('Unsupported regex flag');
        });

        it('should reject potentially unsafe regex patterns', () => {
            const result = validateRegexCleanupRule({
                name: 'Nested quantifier',
                pattern: '(.+)+',
                flags: '',
            });

            expect(result.valid).toBe(false);
            expect(result.error).toContain('Unsafe regex pattern');
        });

        it('should reject empty names and patterns', () => {
            const noName = validateRegexCleanupRule({
                name: '',
                pattern: 'abc',
                flags: '',
            });
            const noPattern = validateRegexCleanupRule({
                name: 'Test',
                pattern: '',
                flags: '',
            });

            expect(noName.valid).toBe(false);
            expect(noPattern.valid).toBe(false);
        });
    });

    describe('removeUnwantedSentences', () => {
        it('should preserve existing exact sentence removal behavior', () => {
            const content = 'This is a test. Unwanted sentence. More content.';
            const removalList = ['Unwanted sentence.'];
            const expected = 'This is a test.  More content.';
            expect(removeUnwantedSentences(content, removalList)).toBe(expected);
        });
    });

    describe('applyTtsCleanupLines', () => {
        const separatorRule: RegexCleanupRule = {
            id: 'rule-separators',
            name: 'Remove separators',
            pattern: '(?:[-=]){5,}',
            flags: '',
            enabled: true,
            appliesTo: 'both',
        };

        it('should remove long separator lines', () => {
            const input = 'Intro\n-----\nMiddle\n=======\nOutro';
            const output = applyTtsCleanupLines(input, [separatorRule]);
            expect(output).toBe('Intro\n\nMiddle\n\nOutro');
        });

        it('should apply multiple rules in order', () => {
            const rules: RegexCleanupRule[] = [
                separatorRule,
                {
                    id: 'rule-stars',
                    name: 'Remove stars',
                    pattern: '\\*{3,}',
                    flags: '',
                    enabled: true,
                    appliesTo: 'tts',
                },
            ];

            const input = 'A\n*****\n-----\nB';
            const output = applyTtsCleanupLines(input, rules);
            expect(output).toBe('A\n\n\nB');
        });

        it('should keep content if rule is malformed', () => {
            const malformedRule: RegexCleanupRule = {
                ...separatorRule,
                id: 'bad-rule',
                name: 'Bad rule',
                pattern: '[',
            };

            const input = 'Hello\n-----\nWorld';
            const output = applyTtsCleanupLines(input, [malformedRule]);
            expect(output).toBe(input);
        });

        it('should cap total replacements to prevent runaway cleanup', () => {
            const removeEveryCharacterRule: RegexCleanupRule = {
                id: 'rule-all',
                name: 'Remove all chars',
                pattern: '.',
                flags: '',
                enabled: true,
                appliesTo: 'tts',
            };

            const input = 'a'.repeat(6000);
            const output = applyTtsCleanupLines(input, [removeEveryCharacterRule]);

            expect(output.length).toBe(1000);
        });
    });

    describe('applyDownloadCleanup', () => {
        it('should apply sentence removal before regex cleanup in HTML text nodes', () => {
            const html = '<div><p>Intro bad</p><p>=====</p><p>Outro</p></div>';
            const sentenceList = ['bad'];
            const rules: RegexCleanupRule[] = [
                {
                    id: 'rule-separators',
                    name: 'Remove separators',
                    pattern: '(?:=){5,}',
                    flags: '',
                    enabled: true,
                    appliesTo: 'download',
                },
            ];

            const cleaned = applyDownloadCleanup(html, sentenceList, rules);
            expect(cleaned).toContain('Intro ');
            expect(cleaned).not.toContain('bad');
            expect(cleaned).not.toContain('=====');
            expect(cleaned).toContain('Outro');
        });

        it('should not alter script tag contents', () => {
            const html = '<div><script>var x = "=====";</script><p>=====</p></div>';
            const rules: RegexCleanupRule[] = [
                {
                    id: 'rule-separators',
                    name: 'Remove separators',
                    pattern: '(?:=){5,}',
                    flags: '',
                    enabled: true,
                    appliesTo: 'download',
                },
            ];

            const cleaned = applyDownloadCleanup(html, [], rules);
            expect(cleaned).toContain('<script>var x = "=====";</script>');
            expect(cleaned).not.toContain('<p>=====</p>');
        });
    });
});
