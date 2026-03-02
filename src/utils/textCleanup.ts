import * as cheerio from 'cheerio';
import { RegexCleanupRule } from '../types';

const MAX_REGEX_PATTERN_LENGTH = 500;
const MAX_RULE_NAME_LENGTH = 80;
const MAX_TOTAL_REPLACEMENTS = 5000;
const REGEX_FLAG_ORDER = ['g', 'i', 'm', 's', 'u'] as const;
const ALLOWED_REGEX_FLAGS = new Set(REGEX_FLAG_ORDER);
const SKIP_TAGS = new Set(['script', 'style', 'noscript', 'iframe']);
const REPEATING_QUANTIFIER = '(?:\\+|\\*|\\{\\d*,?\\d*\\})';

const RISKY_REGEX_CHECKS = [
    {
        // Nested quantifiers like (.+)+, (.*){2,}, ([a-z]+)*
        pattern: new RegExp(`\\((?:[^()\\\\]|\\\\.)*[+*](?:[^()\\\\]|\\\\.)*\\)\\s*${REPEATING_QUANTIFIER}`),
        reason: 'Nested quantifiers can cause very slow matching.',
    },
    {
        // Wildcards under an outer quantifier like (.*a)+
        pattern: new RegExp(`\\((?:[^()\\\\]|\\\\.)*\\.\\*(?:[^()\\\\]|\\\\.)*\\)\\s*${REPEATING_QUANTIFIER}`),
        reason: 'Patterns with wildcard groups under another quantifier are not allowed.',
    },
    {
        // Backreferences in quantified groups can explode
        pattern: new RegExp(`\\((?:[^()\\\\]|\\\\.)*\\\\\\d+(?:[^()\\\\]|\\\\.)*\\)\\s*${REPEATING_QUANTIFIER}`),
        reason: 'Backreferences inside quantified groups are not allowed.',
    },
] as const;

export interface RegexValidationResult {
    valid: boolean;
    error?: string;
    normalizedFlags?: string;
}

interface CompiledRule {
    ruleId: string;
    regex: RegExp;
}

type CleanupTarget = 'download' | 'tts';

const normalizeRegexFlags = (flags: string): string => {
    const input = (flags || '').trim().toLowerCase();
    const unique = new Set(input.split(''));
    return REGEX_FLAG_ORDER.filter(flag => unique.has(flag)).join('');
};

const detectRegexRisk = (pattern: string): string | null => {
    for (const check of RISKY_REGEX_CHECKS) {
        if (check.pattern.test(pattern)) {
            return check.reason;
        }
    }
    return null;
};

interface ParsedRule {
    pattern: string;
    normalizedFlags: string;
}

const parseRegexCleanupRule = (rule: Pick<RegexCleanupRule, 'name' | 'pattern' | 'flags'>): { parsed?: ParsedRule; error?: string } => {
    const name = (rule.name || '').trim();
    const pattern = (rule.pattern || '').trim();
    const flags = (rule.flags || '').trim().toLowerCase();

    if (!name) {
        return { error: 'Rule name is required.' };
    }

    if (name.length > MAX_RULE_NAME_LENGTH) {
        return { error: `Rule name must be ${MAX_RULE_NAME_LENGTH} characters or fewer.` };
    }

    if (!pattern) {
        return { error: 'Regex pattern is required.' };
    }

    if (pattern.length > MAX_REGEX_PATTERN_LENGTH) {
        return { error: `Regex pattern must be ${MAX_REGEX_PATTERN_LENGTH} characters or fewer.` };
    }

    for (const flag of flags) {
        if (!ALLOWED_REGEX_FLAGS.has(flag as typeof REGEX_FLAG_ORDER[number])) {
            return { error: `Unsupported regex flag: "${flag}". Allowed flags: ${REGEX_FLAG_ORDER.join('')}` };
        }
    }

    const riskReason = detectRegexRisk(pattern);
    if (riskReason) {
        return { error: `Unsafe regex pattern: ${riskReason}` };
    }

    const normalizedFlags = normalizeRegexFlags(flags);
    try {
        new RegExp(pattern, normalizedFlags.replace(/g/g, ''));
    } catch (error) {
        return {
            error: error instanceof Error ? `Invalid regex: ${error.message}` : 'Invalid regex pattern.',
        };
    }

    return {
        parsed: {
            pattern,
            normalizedFlags,
        },
    };
};

export const validateRegexCleanupRule = (rule: Pick<RegexCleanupRule, 'name' | 'pattern' | 'flags'>): RegexValidationResult => {
    const parsed = parseRegexCleanupRule(rule);
    if (!parsed.parsed) {
        return { valid: false, error: parsed.error || 'Invalid regex rule.' };
    }

    return { valid: true, normalizedFlags: parsed.parsed.normalizedFlags };
};

export const removeUnwantedSentences = (content: string, sentenceRemovalList: string[]): string => {
    let cleanContent = content;
    for (const sentence of sentenceRemovalList) {
        cleanContent = cleanContent.split(sentence).join('');
    }
    return cleanContent;
};

const isRuleApplicable = (rule: RegexCleanupRule, target: CleanupTarget): boolean => {
    return rule.enabled && (rule.appliesTo === 'both' || rule.appliesTo === target);
};

const compileRules = (rules: RegexCleanupRule[], target: CleanupTarget): CompiledRule[] => {
    const compiled: CompiledRule[] = [];

    for (const rule of rules) {
        if (!isRuleApplicable(rule, target)) continue;

        const parsed = parseRegexCleanupRule(rule);
        if (!parsed.parsed) {
            continue;
        }
        const flagsWithGlobal = parsed.parsed.normalizedFlags.includes('g')
            ? parsed.parsed.normalizedFlags
            : `${parsed.parsed.normalizedFlags}g`;

        try {
            compiled.push({
                ruleId: rule.id,
                regex: new RegExp(parsed.parsed.pattern, flagsWithGlobal),
            });
        } catch (error) {
            console.error(`[TextCleanup] Failed to compile rule ${rule.id}`, error);
        }
    }

    return compiled;
};

const applyCompiledRulesToLine = (line: string, compiledRules: CompiledRule[], replacementState: { total: number }): string => {
    if (replacementState.total >= MAX_TOTAL_REPLACEMENTS) {
        return line;
    }

    let updated = line;

    for (const compiledRule of compiledRules) {
        if (replacementState.total >= MAX_TOTAL_REPLACEMENTS) {
            break;
        }

        try {
            compiledRule.regex.lastIndex = 0;
            updated = updated.replace(compiledRule.regex, (match) => {
                if (replacementState.total >= MAX_TOTAL_REPLACEMENTS) {
                    return match;
                }

                replacementState.total += 1;
                return '';
            });
        } catch (error) {
            console.error(`[TextCleanup] Failed to apply rule ${compiledRule.ruleId}`, error);
        }
    }

    return updated;
};

const cleanTextWithCompiledRules = (
    text: string,
    compiledRules: CompiledRule[],
    replacementState: { total: number }
): string => {
    if (!text || compiledRules.length === 0) return text;

    const lines = text.replace(/\r\n/g, '\n').split('\n');
    const cleanedLines = lines.map(line => applyCompiledRulesToLine(line, compiledRules, replacementState));
    return cleanedLines.join('\n');
};

export const createRegexCleanupRunner = (regexRules: RegexCleanupRule[], target: CleanupTarget): ((text: string) => string) => {
    const compiledRules = compileRules(regexRules, target);
    if (compiledRules.length === 0) {
        return (text: string) => text;
    }

    const replacementState = { total: 0 };
    return (text: string) => cleanTextWithCompiledRules(text, compiledRules, replacementState);
};

export const applyTtsCleanupLines = (text: string, regexRules: RegexCleanupRule[]): string => {
    return createRegexCleanupRunner(regexRules, 'tts')(text);
};

const hasSkippedAncestor = (node: any): boolean => {
    let current = node.parent;
    while (current) {
        if (current.type === 'tag' || current.type === 'script' || current.type === 'style') {
            const tagName = String(current.name || '').toLowerCase();
            if (SKIP_TAGS.has(tagName)) {
                return true;
            }
        }
        current = current.parent;
    }
    return false;
};

export const applyDownloadCleanup = (html: string, sentenceRemovalList: string[], regexRules: RegexCleanupRule[]): string => {
    const cleanedSentenceContent = removeUnwantedSentences(html, sentenceRemovalList);
    const cleanupRunner = createRegexCleanupRunner(regexRules, 'download');

    try {
        const $ = cheerio.load(cleanedSentenceContent);

        const processNode = (node: any) => {
            if (!node) return;

            if (node.type === 'text' && typeof node.data === 'string' && !hasSkippedAncestor(node)) {
                node.data = cleanupRunner(node.data);
            }

            if (Array.isArray(node.children)) {
                node.children.forEach(processNode);
            }
        };

        $('body').contents().each((_, node) => processNode(node));
        return $('body').html() || cleanedSentenceContent;
    } catch (error) {
        console.error('[TextCleanup] Failed to apply download cleanup.', error);
        return cleanedSentenceContent;
    }
};
